package com.hemant.plannerv1.model

import com.hemant.plannerv1.logging.DbgLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * llama.cpp GGUF inference backend — multimodal (vision) + GPU (Vulkan).
 *
 * ## GPU offload
 * When [LlmConfig.gpuLayers] > 0 and the device supports Vulkan, model layers
 * are offloaded to the GPU. Pass gpuLayers=99 to offload all layers (llama.cpp
 * clamps to the actual layer count).
 *
 * ## Vision / multimodal
 * If the mmproj file is present alongside the main model, the clip vision encoder
 * is loaded automatically. Each [generate] call with a non-null [imagePath] will:
 *   1. Embed the screenshot using the clip encoder.
 *   2. Insert the image tokens into the KV cache after the cached static prefix.
 *   3. Eval the dynamic prompt tokens.
 *   4. Generate text.
 *   5. Roll the KV cache back to the prefix boundary.
 *
 * If the mmproj file is absent, the backend falls back to text-only mode
 * silently (screenshots are ignored).
 *
 * ## Prefix caching
 * The static prefix (system instructions, rules, schema, examples) is evaluated
 * once and kept in the KV cache. A [PromptCacheManager] tracks hash changes.
 *
 * ## Threading
 * All public suspend functions switch to [Dispatchers.IO].
 */
class LlamaCppBackend : LlmBackend {

    private var nativeCtxPtr: Long = 0L
    private val cacheManager = PromptCacheManager()
    /** Resolved config — gpuLayers reflects the actual value used (after auto-detect). */
    internal var config: LlmConfig = LlmConfig()
        private set
    /** null = text-only (no mmproj), non-null = discovered mmproj path */
    private var mmProjPath: String? = null

    override val isLoaded: Boolean get() = nativeCtxPtr != 0L

    /** Returns true if a clip vision encoder was successfully loaded. */
    val isVisionEnabled: Boolean
        get() = if (nativeCtxPtr == 0L) false
                else nativeIsVisionEnabled(nativeCtxPtr)

    // ── LlmBackend implementation ─────────────────────────────────────────────

    /**
     * Load the GGUF model. Also discovers the mmproj file in the same directory
     * (any file named `mmproj-*.gguf`) and loads it if found.
     */
    override suspend fun loadModel(modelPath: String, config: LlmConfig) {
        if (isLoaded) {
            DbgLog.d("LlamaCppBackend loadModel skipped: already loaded", tag = "LLAMA_BACKEND")
            return
        }

        withContext(Dispatchers.IO) {
            this@LlamaCppBackend.config = config

            val modelFile = File(modelPath)
            if (!modelFile.exists() || !modelFile.canRead()) {
                throw IllegalArgumentException(
                    "LlamaCppBackend: model file not found or not readable: $modelPath"
                )
            }

            // Auto-discover mmproj alongside the main model
            val discoveredMmProj = modelFile.parentFile
                ?.listFiles { _, name ->
                    name.contains("mmproj", ignoreCase = true) &&
                        name.endsWith(".gguf", ignoreCase = true)
                }
                ?.sortedBy { it.name.lowercase() }
                ?.firstOrNull()
                ?.takeIf { it.canRead() }

            mmProjPath = discoveredMmProj?.absolutePath

            // ── Resolve GPU layers ─────────────────────────────────────────────
            // If gpuLayers == -1 (AUTO_GPU_DETECT), probe Vulkan now.
            // If a compute-capable GPU is found  → offload all layers (99).
            // If no Vulkan GPU is found           → CPU-only (0).
            // If gpuLayers >= 0, use it as-is (manual override).
            val effectiveGpuLayers: Int
            if (config.gpuLayers == BackendConfig.AUTO_GPU_DETECT) {
                val gpuInfo = detectVulkanGpu()
                effectiveGpuLayers = if (gpuInfo.computeGpuCount > 0) 99 else 0
                DbgLog.i(
                    "LlamaCppBackend GPU auto-detect: " +
                        "computeGpuCount=${gpuInfo.computeGpuCount} " +
                        "primaryGpu=\"${gpuInfo.primaryDeviceName}\" " +
                        "effectiveGpuLayers=$effectiveGpuLayers",
                    tag = "LLAMA_BACKEND",
                )
            } else {
                effectiveGpuLayers = config.gpuLayers
                DbgLog.i(
                    "LlamaCppBackend GPU manual override: gpuLayers=$effectiveGpuLayers",
                    tag = "LLAMA_BACKEND",
                )
            }
            // Store the resolved value so it shows in logs and Ready state
            this@LlamaCppBackend.config = config.copy(gpuLayers = effectiveGpuLayers)

            DbgLog.i(
                "LlamaCppBackend loadModel start " +
                    "path=$modelPath " +
                    "mmproj=${mmProjPath ?: "(none — text-only)"} " +
                    "contextLength=${this@LlamaCppBackend.config.contextLength} " +
                    "threads=${this@LlamaCppBackend.config.threadCount} " +
                    "gpuLayers=${this@LlamaCppBackend.config.gpuLayers} " +
                    "bytes=${modelFile.length()}",
                tag = "LLAMA_BACKEND",
            )

            var ptr = 0L
            val loadMs = measureTimeMillis {
                ptr = nativeLoadModel(
                    modelPath   = modelPath,
                    mmProjPath  = mmProjPath ?: "",
                    contextLen  = this@LlamaCppBackend.config.contextLength,
                    threadCount = this@LlamaCppBackend.config.threadCount,
                    gpuLayers   = this@LlamaCppBackend.config.gpuLayers,
                )
            }

            if (ptr == 0L) {
                throw IllegalStateException(
                    "LlamaCppBackend: nativeLoadModel returned null — invalid GGUF, OOM, " +
                        "or llama.cpp native dependency unavailable. " +
                        "path=$modelPath"
                )
            }

            nativeCtxPtr = ptr
            val nativeGpuLayers = nativeGetGpuLayers(nativeCtxPtr)
            if (nativeGpuLayers != this@LlamaCppBackend.config.gpuLayers) {
                DbgLog.w(
                    "LlamaCppBackend GPU layer count adjusted by native backend: " +
                        "requested=${this@LlamaCppBackend.config.gpuLayers} actual=$nativeGpuLayers",
                    tag = "LLAMA_BACKEND",
                )
                this@LlamaCppBackend.config =
                    this@LlamaCppBackend.config.copy(gpuLayers = nativeGpuLayers)
            }
            DbgLog.i(
                "LlamaCppBackend loadModel done loadMs=$loadMs " +
                    "vision=${isVisionEnabled} gpuLayers=${this@LlamaCppBackend.config.gpuLayers}",
                tag = "LLAMA_BACKEND",
            )
        }
    }

    override suspend fun generate(
        staticPrefix:  String,
        dynamicPrompt: String,
        imagePath:     String?,
    ): String {
        check(isLoaded) { "LlamaCppBackend: model not loaded — call loadModel() first" }

        return withContext(Dispatchers.IO) {
            val totalStart = System.currentTimeMillis()

            // ── 1. Prefix cache check ────────────────────────────────────────
            val cacheResult = cacheManager.checkPrefix(staticPrefix)
            val prefixMs: Long

            when (cacheResult) {
                is PromptCacheManager.CacheHit -> {
                    prefixMs = 0L
                    DbgLog.i(
                        "LlamaCppBackend prefix CACHE HIT — skipping prefix eval",
                        tag = "LLAMA_BACKEND",
                    )
                }

                is PromptCacheManager.CacheMiss -> {
                    DbgLog.i(
                        "LlamaCppBackend prefix CACHE MISS reason=${cacheResult.reason}",
                        tag = "LLAMA_BACKEND",
                    )
                    nativeClearKvCache(nativeCtxPtr)

                    var prefixTokenCount = 0
                    prefixMs = measureTimeMillis {
                        prefixTokenCount = nativeEvalPrefix(nativeCtxPtr, staticPrefix)
                    }

                    if (prefixTokenCount < 0) {
                        throw IllegalStateException(
                            "LlamaCppBackend: nativeEvalPrefix failed (returned $prefixTokenCount)"
                        )
                    }

                    cacheManager.onPrefixCached(staticPrefix, prefixTokenCount)
                    DbgLog.i(
                        "LlamaCppBackend prefix eval done " +
                            "prefixTokenCount=$prefixTokenCount prefixMs=$prefixMs",
                        tag = "LLAMA_BACKEND",
                    )
                }
            }

            // ── 2. Generation ────────────────────────────────────────────────
            // Pass imagePath to JNI — JNI embeds the image if clip is loaded
            // and the path is non-empty.
            var output = ""
            val genMs = measureTimeMillis {
                output = nativeGenerate(
                    ctxPtr        = nativeCtxPtr,
                    dynamicPrompt = dynamicPrompt,
                    imagePath     = imagePath?.takeIf { it.isNotBlank() } ?: "",
                    maxNewTokens  = config.maxNewTokens,
                    temperature   = config.temperature,
                    topK          = config.topK,
                    topP          = config.topP,
                )
            }

            // ── 3. Overflow check ────────────────────────────────────────────
            if (output == OVERFLOW_SENTINEL) {
                throw IllegalStateException(
                    "LlamaCppBackend: context overflow — prompt too long for " +
                        "contextLength=${config.contextLength}"
                )
            }

            // ── 4. Logging ───────────────────────────────────────────────────
            val totalMs             = System.currentTimeMillis() - totalStart
            val outputTokenEstimate = output.length / AVG_CHARS_PER_TOKEN
            val tokensPerSec        = if (genMs > 0) outputTokenEstimate * 1000.0 / genMs else 0.0
            val hasImage            = !imagePath.isNullOrBlank()

            DbgLog.i(
                "LlamaCppBackend generate done " +
                    "prefixMs=$prefixMs genMs=$genMs totalMs=$totalMs " +
                    "outputChars=${output.length} " +
                    "~tokensPerSec=${"%.1f".format(tokensPerSec)} " +
                    "cacheHit=${cacheResult is PromptCacheManager.CacheHit} " +
                    "vision=$hasImage visionEnabled=$isVisionEnabled",
                tag = "LLAMA_BACKEND",
            )

            output
        }
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) {
            DbgLog.i("LlamaCppBackend release ptr=$nativeCtxPtr", tag = "LLAMA_BACKEND")
            if (nativeCtxPtr != 0L) {
                nativeFreeModel(nativeCtxPtr)
                nativeCtxPtr = 0L
            }
            mmProjPath = null
            cacheManager.invalidate()
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    /**
     * Load GGUF model + optional mmproj clip model. Returns opaque pointer or 0.
     * Pass empty string for [mmProjPath] for text-only mode.
     */
    private external fun nativeLoadModel(
        modelPath:   String,
        mmProjPath:  String,
        contextLen:  Int,
        threadCount: Int,
        gpuLayers:   Int,
    ): Long

    private external fun nativeFreeModel(ctxPtr: Long)
    private external fun nativeClearKvCache(ctxPtr: Long)
    private external fun nativeGetKvCacheTokenCount(ctxPtr: Long): Int
    private external fun nativeGetGpuLayers(ctxPtr: Long): Int

    /** Returns true if a clip model was successfully loaded at init time. */
    private external fun nativeIsVisionEnabled(ctxPtr: Long): Boolean

    private external fun nativeEvalPrefix(ctxPtr: Long, prefix: String): Int

    /**
     * Generate text. Pass [imagePath] to embed a screenshot before the dynamic
     * prompt. Pass empty string to skip image embedding (text-only call).
     */
    private external fun nativeGenerate(
        ctxPtr:        Long,
        dynamicPrompt: String,
        imagePath:     String,
        maxNewTokens:  Int,
        temperature:   Float,
        topK:          Int,
        topP:          Float,
    ): String

    companion object {
        const val OVERFLOW_SENTINEL     = "__OVERFLOW__"
        private const val AVG_CHARS_PER_TOKEN = 4

        /**
         * Probe the device for Vulkan compute-capable GPUs.
         *
         * This is a lightweight, stateless call (no model loaded). Safe to call
         * before [loadModel] to decide how many GPU layers to use.
         *
         * Result fields:
         *  - [VulkanGpuInfo.computeGpuCount]: number of Vulkan devices with compute queues
         *  - [VulkanGpuInfo.primaryDeviceName]: name of the first compute device (e.g. "Adreno 750")
         */
        fun detectVulkanGpu(): VulkanGpuInfo {
            return try {
                val raw = nativeDetectVulkanGpu()   // "count=N name=DeviceName"
                val count = raw
                    .substringAfter("count=")
                    .substringBefore(" ")
                    .toIntOrNull() ?: 0
                val name = raw.substringAfter("name=", missingDelimiterValue = "")
                VulkanGpuInfo(computeGpuCount = count, primaryDeviceName = name)
            } catch (e: Exception) {
                DbgLog.w("LlamaCppBackend.detectVulkanGpu failed: ${e.message} — assuming no GPU")
                VulkanGpuInfo(computeGpuCount = 0, primaryDeviceName = "")
            }
        }

        @JvmStatic
        private external fun nativeDetectVulkanGpu(): String

        init {
            System.loadLibrary("llama_jni")
        }
    }
}

/**
 * Result of a Vulkan GPU probe performed before model loading.
 *
 * @param computeGpuCount number of physical Vulkan devices that expose a compute queue.
 *                        0 means no usable GPU (Vulkan unavailable or only graphics-only devices).
 * @param primaryDeviceName driver-reported name of the first compute device
 *                          (e.g. "Adreno 750", "Mali-G710", "Immortalis-G715").
 */
data class VulkanGpuInfo(
    val computeGpuCount: Int,
    val primaryDeviceName: String,
) {
    val hasGpu: Boolean get() = computeGpuCount > 0
}
