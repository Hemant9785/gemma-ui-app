package com.hemant.plannerv1.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists backend selection and [LlmConfig] parameters to SharedPreferences.
 *
 * All reads return the current persisted value; all writes persist immediately.
 * Instantiate once in [com.hemant.plannerv1.AppContainer].
 */
class BackendConfig(context: Context) {

    enum class BackendType {
        /** Google LiteRT-LM (original backend — default) */
        LITERTLM,

        /** llama.cpp GGUF via JNI */
        LLAMACPP,
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Backend selection ────────────────────────────────────────────────────

    var activeBackend: BackendType
        get() = BackendType.valueOf(
            prefs.getString(KEY_BACKEND, BackendType.LITERTLM.name)!!
        )
        set(value) = prefs.edit { putString(KEY_BACKEND, value.name) }

    // ── LlmConfig fields ─────────────────────────────────────────────────────

    var contextLength: Int
        get() = prefs.getInt(KEY_CTX_LEN, DEFAULT_CTX_LEN)
        set(value) = prefs.edit { putInt(KEY_CTX_LEN, value) }

    var maxNewTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = prefs.edit { putInt(KEY_MAX_TOKENS, value) }

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
        set(value) = prefs.edit { putFloat(KEY_TEMPERATURE, value) }

    var topK: Int
        get() = prefs.getInt(KEY_TOP_K, DEFAULT_TOP_K)
        set(value) = prefs.edit { putInt(KEY_TOP_K, value) }

    var topP: Float
        get() = prefs.getFloat(KEY_TOP_P, DEFAULT_TOP_P)
        set(value) = prefs.edit { putFloat(KEY_TOP_P, value) }

    var threadCount: Int
        get() = prefs.getInt(KEY_THREADS, DEFAULT_THREADS)
        set(value) = prefs.edit { putInt(KEY_THREADS, value) }

    var gpuLayers: Int
        get() = prefs.getInt(KEY_GPU_LAYERS, DEFAULT_GPU_LAYERS)
        set(value) = prefs.edit { putInt(KEY_GPU_LAYERS, value) }

    /** Snapshot the current config as an immutable [LlmConfig]. */
    fun toLlmConfig(): LlmConfig = LlmConfig(
        contextLength = contextLength,
        maxNewTokens  = maxNewTokens,
        temperature   = temperature,
        topK          = topK,
        topP          = topP,
        threadCount   = threadCount,
        gpuLayers     = gpuLayers,
    )

    companion object {
        private const val PREFS_NAME = "llm_backend_config"

        private const val KEY_BACKEND     = "active_backend"
        private const val KEY_CTX_LEN     = "context_length"
        private const val KEY_MAX_TOKENS  = "max_new_tokens"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_K       = "top_k"
        private const val KEY_TOP_P       = "top_p"
        private const val KEY_THREADS     = "thread_count"
        private const val KEY_GPU_LAYERS  = "gpu_layers"

        /** Sentinel: detect GPU automatically at model load time. */
        const val AUTO_GPU_DETECT = -1

        // Defaults tuned for Gemma 4 E4B Q4_K_M.
        //
        // gpuLayers meaning:
        //   AUTO_GPU_DETECT (-1) = probe Vulkan at runtime; use GPU if found, else CPU
        //                  0    = force CPU-only (safe mode)
        //                 >0    = manually offload exactly N layers (99 = all layers)
        private const val DEFAULT_CTX_LEN     = 4096
        private const val DEFAULT_MAX_TOKENS  = 512
        private const val DEFAULT_TEMPERATURE = 0.0f
        private const val DEFAULT_TOP_K       = 1
        private const val DEFAULT_TOP_P       = 0.95f
        private const val DEFAULT_THREADS     = 4
        private const val DEFAULT_GPU_LAYERS  = AUTO_GPU_DETECT
    }
}
