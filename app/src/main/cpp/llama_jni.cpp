/**
 * llama_jni.cpp
 *
 * JNI bridge: Kotlin ←→ llama.cpp + llava (multimodal vision)
 *
 * Threading contract:
 *   All functions are expected from a single background thread (Dispatchers.IO).
 *
 * Memory contract:
 *   nativeLoadModel  → returns opaque LlamaContext* as jlong
 *   nativeFreeModel  → frees model + context + clip; caller must null the handle
 *
 * Vision / multimodal:
 *   If an mmproj path is provided at load time, a clip_ctx is created.
 *   nativeGenerate accepts an optional imagePath; if non-empty and clip is
 *   loaded, the image is embedded into the KV cache between the cached static
 *   prefix and the dynamic prompt tokens.
 *
 * GPU:
 *   n_gpu_layers is passed through only when this build enables GGML Vulkan.
 *   The default Android build is CPU-only because recent llama.cpp Vulkan
 *   builds require external SPIRV-Headers/SPIRV-Tools packages.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>

#ifndef LLAMA_JNI_HAS_VULKAN
#define LLAMA_JNI_HAS_VULKAN 0
#endif

#if LLAMA_JNI_HAS_VULKAN
#include <vulkan/vulkan.h>
#endif

#include "llama.cpp/include/llama.h"

#ifndef LLAMA_JNI_HAS_LEGACY_LLAVA
#define LLAMA_JNI_HAS_LEGACY_LLAVA 0
#endif

#ifndef LLAMA_JNI_MODERN_API
#define LLAMA_JNI_MODERN_API 0
#endif

#if LLAMA_JNI_HAS_LEGACY_LLAVA
#include "llava.h"
#endif

#if LLAMA_JNI_MODERN_API
#include "mtmd.h"
#include "mtmd-helper.h"
#endif

#define LOG_TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Vulkan GPU detection
// ---------------------------------------------------------------------------
struct GpuInfo {
    int         count = 0;
    std::string primaryDeviceName;
};

/**
 * Enumerate Vulkan physical devices that expose a compute queue.
 * Returns count=0 when Vulkan is unavailable or has no compute-capable device.
 * Does NOT require a surface / window — pure compute check.
 */
static GpuInfo detect_vulkan_gpus() {
    GpuInfo result;

#if !LLAMA_JNI_HAS_VULKAN
    LOGI("detect_vulkan_gpus: GGML Vulkan backend disabled in this build");
    return result;
#else
    VkApplicationInfo appInfo  = {};
    appInfo.sType              = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName   = "llama_detect";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion         = VK_API_VERSION_1_1;

    VkInstanceCreateInfo ci = {};
    ci.sType                = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo     = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&ci, nullptr, &instance) != VK_SUCCESS) {
        LOGW("detect_vulkan_gpus: vkCreateInstance failed — Vulkan unavailable");
        return result;
    }

    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);

    if (deviceCount > 0) {
        std::vector<VkPhysicalDevice> devices(deviceCount);
        vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

        for (auto& dev : devices) {
            // Check that the device exposes at least one compute queue family
            uint32_t qfCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(dev, &qfCount, nullptr);
            std::vector<VkQueueFamilyProperties> qfs(qfCount);
            vkGetPhysicalDeviceQueueFamilyProperties(dev, &qfCount, qfs.data());

            bool hasCompute = false;
            for (auto& qf : qfs) {
                if (qf.queueFlags & VK_QUEUE_COMPUTE_BIT) {
                    hasCompute = true;
                    break;
                }
            }

            if (hasCompute) {
                VkPhysicalDeviceProperties props;
                vkGetPhysicalDeviceProperties(dev, &props);
                result.count++;
                if (result.count == 1) {
                    result.primaryDeviceName = std::string(props.deviceName);
                }
                LOGI("detect_vulkan_gpus: found compute GPU #%d: %s (type=%d)",
                     result.count, props.deviceName, props.deviceType);
            }
        }
    }

    vkDestroyInstance(instance, nullptr);
    LOGI("detect_vulkan_gpus: total compute-capable GPUs = %d", result.count);
    return result;
#endif
}

// ---------------------------------------------------------------------------
// Internal context struct
// ---------------------------------------------------------------------------
struct LlamaContext {
    llama_model*   model    = nullptr;
    llama_context* ctx      = nullptr;
#if LLAMA_JNI_HAS_LEGACY_LLAVA
    clip_ctx*      clip     = nullptr;   // null when no mmproj loaded
#endif
#if LLAMA_JNI_MODERN_API
    mtmd_context*  mtmd     = nullptr;   // null when no modern mmproj loaded
#endif
    int            n_ctx    = 4096;
    int            n_threads = 4;
    int            effective_gpu_layers = 0;
    // How many prefix tokens are in the KV cache (0 = cache is empty)
    int            kv_prefix_token_count = 0;
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

static jstring string_to_jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

static void ensure_llama_backend_initialized() {
    static bool initialized = false;
    if (!initialized) {
        llama_backend_init();
        initialized = true;
        LOGI("llama_backend_init done");
    }
}

static void clear_context_memory(llama_context* ctx) {
    llama_memory_clear(llama_get_memory(ctx), true);
}

static bool remove_memory_after_prefix(llama_context* ctx, int prefix_token_count) {
    return llama_memory_seq_rm(
        llama_get_memory(ctx),
        0,
        static_cast<llama_pos>(prefix_token_count),
        -1);
}

// Tokenise text. Returns token count, -1 on error.
static int tokenise(llama_context* ctx,
                    const std::string& text,
                    std::vector<llama_token>& out_tokens,
                    bool add_special,
                    bool parse_special) {
    const llama_model* model = llama_get_model(ctx);
    const llama_vocab* vocab = llama_model_get_vocab(model);
    const int n_max = static_cast<int>(text.size()) + 64;
    out_tokens.resize(n_max);
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           out_tokens.data(), n_max, add_special, parse_special);
    if (n < 0) {
        out_tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           out_tokens.data(), -n, add_special, parse_special);
    }
    if (n > 0) out_tokens.resize(n);
    return n;
}

// ---------------------------------------------------------------------------
// JNI: nativeLoadModel
// mmProjPath may be empty — if so, clip is not loaded (text-only mode).
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jlong JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeLoadModel(
        JNIEnv* env, jobject /* this */,
        jstring jModelPath,
        jstring jMmProjPath,
        jint    jContextLen,
        jint    jThreadCount,
        jint    jGpuLayers) {

    const std::string model_path   = jstring_to_string(env, jModelPath);
    const std::string mmproj_path  = jstring_to_string(env, jMmProjPath);
    const int context_len          = static_cast<int>(jContextLen);
    const int thread_count         = static_cast<int>(jThreadCount);
    const int gpu_layers           = static_cast<int>(jGpuLayers);

    LOGI("nativeLoadModel path=%s mmproj=%s ctx=%d threads=%d gpu_layers=%d",
         model_path.c_str(),
         mmproj_path.empty() ? "(none)" : mmproj_path.c_str(),
         context_len, thread_count, gpu_layers);

    ensure_llama_backend_initialized();

    // ── Load main model ─────────────────────────────────────────────────────
    llama_model_params mparams  = llama_model_default_params();
    int effective_gpu_layers = gpu_layers;
#if !LLAMA_JNI_HAS_VULKAN
    if (gpu_layers > 0) {
        LOGW("nativeLoadModel requested gpu_layers=%d but GGML Vulkan is disabled; forcing CPU",
             gpu_layers);
    }
    effective_gpu_layers = 0;
#endif
    mparams.n_gpu_layers = effective_gpu_layers;

    llama_model* model = llama_model_load_from_file(model_path.c_str(), mparams);

    // GPU-to-CPU automatic fallback:
    // If the GPU load fails (driver crash, OOM, unsupported ops), retry with
    // CPU-only. This makes the app self-healing on devices with problematic
    // Vulkan drivers without requiring a manual config change.
    if (!model && effective_gpu_layers > 0) {
        LOGW("nativeLoadModel GPU load failed — retrying with CPU (gpu_layers=0)");
        mparams.n_gpu_layers = 0;
        effective_gpu_layers = 0;
        model = llama_model_load_from_file(model_path.c_str(), mparams);
        if (model) {
            LOGI("nativeLoadModel CPU fallback succeeded");
        }
    }

    if (!model) {
        LOGE("Failed to load model: %s", model_path.c_str());
        return 0L;
    }

    // ── Create context ──────────────────────────────────────────────────────
    llama_context_params cparams  = llama_context_default_params();
    cparams.n_ctx                 = static_cast<uint32_t>(context_len);
    cparams.n_threads             = static_cast<int32_t>(thread_count);
    cparams.n_threads_batch       = static_cast<int32_t>(thread_count);
#if LLAMA_JNI_MODERN_API
    cparams.flash_attn_type       = LLAMA_FLASH_ATTN_TYPE_AUTO;
#else
    cparams.flash_attn            = true;   // faster prefill
#endif

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(model);
        return 0L;
    }

    // ── Optionally load vision encoder (clip / mmproj) ──────────────────────
#if LLAMA_JNI_HAS_LEGACY_LLAVA
    clip_ctx* clip = nullptr;
    if (!mmproj_path.empty()) {
        clip = clip_model_load(mmproj_path.c_str(), /*verbosity=*/1);
        if (!clip) {
            LOGW("Failed to load mmproj from %s — vision disabled", mmproj_path.c_str());
            // Non-fatal: continue in text-only mode
        } else {
            LOGI("mmproj loaded OK: %s", mmproj_path.c_str());
        }
    }
#elif LLAMA_JNI_MODERN_API
    mtmd_context* mtmd = nullptr;
    if (!mmproj_path.empty()) {
        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu = effective_gpu_layers > 0;
        mtmd_params.print_timings = true;
        mtmd_params.n_threads = thread_count;
        mtmd_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        mtmd_params.warmup = false;
        mtmd = mtmd_init_from_file(mmproj_path.c_str(), model, mtmd_params);
        if (!mtmd) {
            LOGW("Failed to load mtmd mmproj from %s — vision disabled", mmproj_path.c_str());
        } else {
            LOGI("mtmd mmproj loaded OK: %s vision=%s audio=%s marker=%s",
                 mmproj_path.c_str(),
                 mtmd_support_vision(mtmd) ? "ON" : "OFF",
                 mtmd_support_audio(mtmd) ? "ON" : "OFF",
                 mtmd_get_marker(mtmd));
        }
    }
#else
    if (!mmproj_path.empty()) {
        LOGW("mmproj found but no multimodal bridge is available in this llama.cpp checkout; vision disabled");
    }
#endif

    auto* lctx             = new LlamaContext();
    lctx->model            = model;
    lctx->ctx              = ctx;
#if LLAMA_JNI_HAS_LEGACY_LLAVA
    lctx->clip             = clip;
#endif
#if LLAMA_JNI_MODERN_API
    lctx->mtmd             = mtmd;
#endif
    lctx->n_ctx            = context_len;
    lctx->n_threads        = thread_count;
    lctx->effective_gpu_layers = effective_gpu_layers;
    lctx->kv_prefix_token_count = 0;

    LOGI("nativeLoadModel success ptr=%p vision=%s effective_gpu_layers=%d",
         lctx,
#if LLAMA_JNI_HAS_LEGACY_LLAVA
         clip ? "ON" : "OFF",
#elif LLAMA_JNI_MODERN_API
         (mtmd && mtmd_support_vision(mtmd)) ? "ON" : "OFF",
#else
         "OFF",
#endif
         effective_gpu_layers);
    return reinterpret_cast<jlong>(lctx);
}

// ---------------------------------------------------------------------------
// JNI: nativeFreeModel
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeFreeModel(
        JNIEnv* /* env */, jobject /* this */, jlong jPtr) {

    if (!jPtr) return;
    auto* lctx = reinterpret_cast<LlamaContext*>(jPtr);
    LOGI("nativeFreeModel ptr=%p", lctx);
#if LLAMA_JNI_HAS_LEGACY_LLAVA
    if (lctx->clip)  clip_free(lctx->clip);
#endif
#if LLAMA_JNI_MODERN_API
    if (lctx->mtmd)  mtmd_free(lctx->mtmd);
#endif
    if (lctx->ctx)   llama_free(lctx->ctx);
    if (lctx->model) llama_model_free(lctx->model);
    delete lctx;
}

// ---------------------------------------------------------------------------
// JNI: nativeClearKvCache
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeClearKvCache(
        JNIEnv* /* env */, jobject /* this */, jlong jPtr) {

    if (!jPtr) return;
    auto* lctx = reinterpret_cast<LlamaContext*>(jPtr);
    clear_context_memory(lctx->ctx);
    lctx->kv_prefix_token_count = 0;
    LOGI("nativeClearKvCache done");
}

// ---------------------------------------------------------------------------
// JNI: nativeGetKvCacheTokenCount
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jint JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeGetKvCacheTokenCount(
        JNIEnv* /* env */, jobject /* this */, jlong jPtr) {

    if (!jPtr) return 0;
    auto* lctx = reinterpret_cast<LlamaContext*>(jPtr);
    return static_cast<jint>(lctx->kv_prefix_token_count);
}

// ---------------------------------------------------------------------------
// JNI: nativeGetGpuLayers
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jint JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeGetGpuLayers(
        JNIEnv* /* env */, jobject /* this */, jlong jPtr) {

    if (!jPtr) return 0;
    auto* lctx = reinterpret_cast<LlamaContext*>(jPtr);
    return static_cast<jint>(lctx->effective_gpu_layers);
}

// ---------------------------------------------------------------------------
// JNI: nativeEvalPrefix
// Tokenises and decodes the static prefix, filling the KV cache.
// Returns the number of prefix tokens processed, or -1 on error.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jint JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeEvalPrefix(
        JNIEnv* env, jobject /* this */,
        jlong   jPtr,
        jstring jPrefix) {

    if (!jPtr) return -1;
    auto* lctx = reinterpret_cast<LlamaContext*>(jPtr);
    const std::string prefix = jstring_to_string(env, jPrefix);

    clear_context_memory(lctx->ctx);
    lctx->kv_prefix_token_count = 0;
    llama_perf_context_reset(lctx->ctx);

    std::vector<llama_token> tokens;
    int n_tokens = tokenise(lctx->ctx, prefix, tokens,
                            /*add_special=*/true, /*parse_special=*/true);
    if (n_tokens <= 0) {
        LOGE("nativeEvalPrefix tokenise failed n=%d", n_tokens);
        return -1;
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(lctx->ctx, batch) != 0) {
        LOGE("nativeEvalPrefix llama_decode failed");
        return -1;
    }

    lctx->kv_prefix_token_count = n_tokens;
    LOGI("nativeEvalPrefix done prefix_tokens=%d", n_tokens);
    return static_cast<jint>(n_tokens);
}

// ---------------------------------------------------------------------------
// JNI: nativeIsVisionEnabled
// Returns 1 if a clip model is loaded, 0 otherwise.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeIsVisionEnabled(
        JNIEnv* /* env */, jobject /* this */, jlong jPtr) {

    if (!jPtr) return JNI_FALSE;
#if LLAMA_JNI_HAS_LEGACY_LLAVA
    auto* lctx = reinterpret_cast<LlamaContext*>(jPtr);
    return lctx->clip ? JNI_TRUE : JNI_FALSE;
#elif LLAMA_JNI_MODERN_API
    auto* lctx = reinterpret_cast<LlamaContext*>(jPtr);
    return (lctx->mtmd && mtmd_support_vision(lctx->mtmd)) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

// ---------------------------------------------------------------------------
// JNI: nativeGenerate
//
// Flow:
//   1. If imagePath is non-empty and clip is loaded: embed image after prefix.
//   2. Tokenise dynamicPrompt and decode.
//   3. Generate up to maxNewTokens.
//   4. Roll back KV cache to the prefix boundary so the next call reuses it.
//
// Returns the generated text, or "__OVERFLOW__" on context overflow.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeGenerate(
        JNIEnv* env, jobject /* this */,
        jlong   jPtr,
        jstring jDynamicPrompt,
        jstring jImagePath,
        jint    jMaxNewTokens,
        jfloat  jTemperature,
        jint    jTopK,
        jfloat  jTopP) {

    if (!jPtr) return string_to_jstring(env, "");
    auto* lctx                     = reinterpret_cast<LlamaContext*>(jPtr);
    const std::string dynamic      = jstring_to_string(env, jDynamicPrompt);
    const std::string image_path   = jstring_to_string(env, jImagePath);
    const int max_new_tokens       = static_cast<int>(jMaxNewTokens);

    // Track KV position as we go (starts at end of cached prefix)
    int n_past = lctx->kv_prefix_token_count;
    auto rollback_to_prefix = [&]() {
        if (!remove_memory_after_prefix(lctx->ctx, lctx->kv_prefix_token_count)) {
            LOGW("nativeGenerate rollback_to_prefix reported partial memory removal");
        }
    };

    // ── 1. Image embedding ───────────────────────────────────────────────────
    // Image tokens are NOT cached (screenshot changes every step), so they are
    // always re-evaluated from the prefix boundary position.
    int n_image_tokens = 0;
#if LLAMA_JNI_HAS_LEGACY_LLAVA
    if (!image_path.empty() && lctx->clip != nullptr) {
        llava_image_embed* embed =
            llava_image_embed_make_with_filename(lctx->clip, lctx->n_threads, image_path.c_str());
        if (embed) {
            bool ok = llava_eval_image_embed(lctx->ctx, embed, /*n_batch=*/512, &n_past);
            n_image_tokens = n_past - lctx->kv_prefix_token_count;
            llava_image_embed_free(embed);
            if (!ok) {
                LOGE("nativeGenerate llava_eval_image_embed failed");
                // Non-fatal: continue without image context
                rollback_to_prefix();
                n_past = lctx->kv_prefix_token_count;
                n_image_tokens = 0;
            } else {
                LOGI("nativeGenerate image embedded tokens=%d n_past=%d", n_image_tokens, n_past);
            }
        } else {
            LOGW("nativeGenerate llava_image_embed_make_with_filename failed path=%s",
                 image_path.c_str());
        }
    }
#elif !LLAMA_JNI_MODERN_API
    if (!image_path.empty()) {
        LOGW("nativeGenerate image ignored: no multimodal bridge is available in this build");
    }
#endif

    // ── 2. Dynamic prompt tokens ─────────────────────────────────────────────
    int n_dyn = 0;
#if LLAMA_JNI_MODERN_API
    if (!image_path.empty() && (lctx->mtmd == nullptr || !mtmd_support_vision(lctx->mtmd))) {
        LOGW("nativeGenerate image ignored: mtmd vision not enabled (mmproj missing or unsupported)");
    }

    if (!image_path.empty() && lctx->mtmd != nullptr && mtmd_support_vision(lctx->mtmd)) {
        mtmd_helper_bitmap_wrapper bitmap_wrapper =
            mtmd_helper_bitmap_init_from_file(lctx->mtmd, image_path.c_str(), false);
        if (!bitmap_wrapper.bitmap) {
            LOGW("nativeGenerate mtmd bitmap load failed path=%s; falling back to text-only",
                 image_path.c_str());
        } else {
            std::string mtmd_prompt = std::string(mtmd_get_marker(lctx->mtmd)) + "\n" + dynamic;
            mtmd_input_text text;
            text.text = mtmd_prompt.c_str();
            text.add_special = false;
            text.parse_special = true;

            mtmd_input_chunks* chunks = mtmd_input_chunks_init();
            const mtmd_bitmap* bitmaps[] = { bitmap_wrapper.bitmap };
            int32_t tokenized = mtmd_tokenize(
                lctx->mtmd,
                chunks,
                &text,
                bitmaps,
                1);

            mtmd_bitmap_free(bitmap_wrapper.bitmap);
            if (bitmap_wrapper.video_ctx) {
                mtmd_helper_video_free(bitmap_wrapper.video_ctx);
            }

            if (tokenized != 0) {
                LOGE("nativeGenerate mtmd_tokenize failed res=%d", tokenized);
                mtmd_input_chunks_free(chunks);
                rollback_to_prefix();
                return string_to_jstring(env, "");
            }

            n_dyn = static_cast<int>(mtmd_helper_get_n_pos(chunks));
            if (n_past + n_dyn >= lctx->n_ctx - 4) {
                LOGW("nativeGenerate overflow after mtmd tokenize: used=%d n_ctx=%d",
                     n_past + n_dyn, lctx->n_ctx);
                mtmd_input_chunks_free(chunks);
                rollback_to_prefix();
                return string_to_jstring(env, "__OVERFLOW__");
            }

            llama_pos new_n_past = n_past;
            int32_t eval_res = mtmd_helper_eval_chunks(
                lctx->mtmd,
                lctx->ctx,
                chunks,
                n_past,
                0,
                512,
                true,
                &new_n_past);
            mtmd_input_chunks_free(chunks);

            if (eval_res != 0) {
                LOGE("nativeGenerate mtmd_helper_eval_chunks failed res=%d", eval_res);
                rollback_to_prefix();
                return string_to_jstring(env, "");
            }

            n_image_tokens = n_dyn;
            n_past = static_cast<int>(new_n_past);
            LOGI("nativeGenerate mtmd image+text evaluated pos=%d n_past=%d",
                 n_dyn, n_past);
        }
    }
#endif

    if (n_dyn == 0) {
        std::vector<llama_token> dyn_tokens;
        n_dyn = tokenise(lctx->ctx, dynamic, dyn_tokens,
                             /*add_special=*/false, /*parse_special=*/true);
        if (n_dyn <= 0) {
            LOGE("nativeGenerate tokenise dynamic failed n=%d", n_dyn);
            rollback_to_prefix();
            return string_to_jstring(env, "");
        }

        // Overflow guard
        int total_used = n_past + n_dyn;
        if (total_used >= lctx->n_ctx - 4) {
            LOGW("nativeGenerate overflow: used=%d n_ctx=%d", total_used, lctx->n_ctx);
            rollback_to_prefix();
            return string_to_jstring(env, "__OVERFLOW__");
        }

        llama_batch dyn_batch = llama_batch_get_one(dyn_tokens.data(), n_dyn);
        if (llama_decode(lctx->ctx, dyn_batch) != 0) {
            LOGE("nativeGenerate llama_decode (dynamic) failed");
            rollback_to_prefix();
            return string_to_jstring(env, "");
        }
        n_past += n_dyn;
    }

    // ── 3. Sampler chain ─────────────────────────────────────────────────────
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (jTemperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(static_cast<int>(jTopK)));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(static_cast<float>(jTopP), 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(static_cast<float>(jTemperature)));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // ── 4. Generation loop ────────────────────────────────────────────────────
    const llama_model* model = llama_get_model(lctx->ctx);
    const llama_vocab* vocab = llama_model_get_vocab(model);
    const llama_token eos    = llama_vocab_eos(vocab);

    std::string result;
    result.reserve(max_new_tokens * 4);

    char token_buf[256];
    for (int i = 0; i < max_new_tokens; ++i) {
        llama_token new_token = llama_sampler_sample(smpl, lctx->ctx, -1);
        llama_sampler_accept(smpl, new_token);
        if (new_token == eos) break;

        int n = llama_token_to_piece(vocab, new_token, token_buf, sizeof(token_buf), 0, true);
        if (n > 0) result.append(token_buf, n);

        // Overflow guard mid-generation
        if (n_past + i + 1 >= lctx->n_ctx - 2) {
            LOGW("nativeGenerate context limit at token=%d", i);
            break;
        }

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(lctx->ctx, next_batch) != 0) {
            LOGE("nativeGenerate decode token %d failed", i);
            break;
        }
    }

    llama_sampler_free(smpl);

    // ── 5. Roll back KV cache to just the prefix ─────────────────────────────
    // Remove image + dynamic + generated tokens so next call starts from the
    // cached prefix again.
    rollback_to_prefix();

    LOGI("nativeGenerate done output_chars=%zu image_tokens=%d dyn_tokens=%d",
         result.size(), n_image_tokens, n_dyn);
    return string_to_jstring(env, result);
}

// ---------------------------------------------------------------------------
// JNI: nativeDetectVulkanGpu  (static — no context pointer needed)
//
// Returns a string in the form:
//   "count=N name=<primary device name>"
//   "count=0 name="   when no Vulkan compute device is found
//
// Called from Kotlin *before* loadModel to decide how many layers to offload.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeDetectVulkanGpu(
        JNIEnv* env, jclass /* cls */) {

    auto info = detect_vulkan_gpus();
    std::string result =
        "count=" + std::to_string(info.count) +
        " name=" + info.primaryDeviceName;
    LOGI("nativeDetectVulkanGpu result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}
