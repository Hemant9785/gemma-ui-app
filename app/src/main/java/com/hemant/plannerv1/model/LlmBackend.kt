package com.hemant.plannerv1.model

/**
 * Unified interface for all LLM inference backends.
 *
 * Threading contract: all suspend functions run on [kotlinx.coroutines.Dispatchers.IO].
 * The caller (LlamaCppBackend / LiteRtBackend) must not call these from the main thread.
 */
interface LlmBackend {

    /**
     * Whether a model is currently loaded and ready for inference.
     * Safe to read from any thread.
     */
    val isLoaded: Boolean

    /**
     * Load the model at [modelPath] and warm up the runtime.
     * Must be called once before [generate]. Calling again while [isLoaded] == true
     * is a no-op.
     *
     * @throws IllegalArgumentException if the model file is missing or unreadable
     * @throws IllegalStateException    if the model file is not a valid format
     * @throws OutOfMemoryError         if the device cannot allocate the required memory
     */
    suspend fun loadModel(modelPath: String, config: LlmConfig)

    /**
     * Run inference and return the raw model output string.
     *
     * [staticPrefix] is the cacheable, rarely-changing part of the prompt (system
     * instructions, rules, schema, examples). Backends that support prefix caching
     * (e.g. llama.cpp KV cache) should process this once and reuse it.
     *
     * [dynamicPrompt] is the per-request, step-specific part (goal, current app,
     * history, screenshot context).
     *
     * [imagePath] is the absolute filesystem path to the screenshot image to include
     * in the multimodal request. Null or blank = text-only (backends that don't
     * support vision, or when no screenshot is available).
     *
     * @throws IllegalStateException if [isLoaded] == false
     */
    suspend fun generate(staticPrefix: String, dynamicPrompt: String, imagePath: String? = null): String

    /**
     * Release all native resources (model weights, KV cache, threads).
     * After this call [isLoaded] returns false.
     * Safe to call even if the model was never loaded.
     */
    suspend fun release()
}

/**
 * Configuration parameters for LLM inference.
 * Defaults are tuned for best latency on Gemma 4 E4B Q4_K_M.
 *
 * Both backends read the fields they understand; unrecognised fields are silently ignored.
 */
data class LlmConfig(
    /** Total KV-cache size in tokens. Must be >= max expected prompt + output length. */
    val contextLength: Int = 4096,

    /** Maximum number of new tokens to generate per request. */
    val maxNewTokens: Int = 512,

    /**
     * Sampling temperature.
     * 0.0 = greedy (deterministic, best for structured JSON output).
     * >0.0 = stochastic sampling.
     */
    val temperature: Float = 0.0f,

    /** Top-K sampling parameter. Ignored when temperature == 0. */
    val topK: Int = 1,

    /** Top-P (nucleus) sampling parameter. Ignored when temperature == 0. */
    val topP: Float = 0.95f,

    /**
     * Number of CPU threads for inference.
     * Recommended: half the number of physical cores (leaves headroom for the OS).
     */
    val threadCount: Int = 4,

    /**
     * Number of model layers to offload to GPU (Vulkan on Android).
     * 0 = CPU-only. Increase gradually; stability depends on device GPU drivers.
     */
    val gpuLayers: Int = 0,
)
