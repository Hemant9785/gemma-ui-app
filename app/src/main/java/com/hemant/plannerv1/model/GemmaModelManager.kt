package com.hemant.plannerv1.model

import android.content.Context
import com.hemant.plannerv1.logging.DbgLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// ── Model state ───────────────────────────────────────────────────────────────

sealed interface ModelState {
    data object NotInitialized : ModelState
    data object MissingAsset : ModelState
    data object CopyingAsset : ModelState
    data object InitializingGpu : ModelState
    data object InitializingCpu : ModelState
    data class Ready(val backend: String, val modelPath: String) : ModelState
    data class Error(val message: String) : ModelState
}

// ── Manager ───────────────────────────────────────────────────────────────────

/**
 * Single entry point for LLM inference, used by [AgentOrchestrator] and [EvalRunner].
 *
 * Delegates all inference to the active [LlmBackend] (either [LiteRtBackend] or
 * [LlamaCppBackend]) as configured in [BackendConfig].
 *
 * The rest of the app (AgentOrchestrator, EvalRunner, UI) does not need to know
 * which backend is active.
 */
class GemmaModelManager(
    private val context: Context,
    private val backendConfig: BackendConfig,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelState>(ModelState.NotInitialized)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Currently active backend instance. Null until [initialize] succeeds. */
    private var activeBackend: LlmBackend? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Initialize the active backend. No-op if already initialized. */
    suspend fun initialize() {
        mutex.withLock {
            initializeLocked()
        }
    }

    /**
     * Run inference and return the raw model output.
     *
     * Calls [initialize] if the backend is not yet loaded (matches the original
     * GemmaModelManager behaviour so callers don't need to change).
     */
    suspend fun generate(request: ModelRequest): String {
        return mutex.withLock {
            initializeLocked()
            val backend = activeBackend ?: error("GemmaModelManager: backend not initialized")

            DbgLog.i(
                "GemmaModelManager generate start screenshot=${request.screenshotPath} " +
                    "promptChars=${request.prompt.length} " +
                    "staticPrefixChars=${request.staticPrefix.length} " +
                    "dynamicPromptChars=${request.dynamicPrompt.length} " +
                    "promptPreview=${DbgLog.preview(request.prompt)}"
            )
            DbgLog.d(
                "FINAL PROMPT TO MODEL screenshot=${request.screenshotPath} " +
                    "chars=${request.prompt.length}\n${request.prompt}",
                tag = "MODEL_DBG",
            )

            try {
                val output = backend.generate(
                    staticPrefix  = request.staticPrefix,
                    dynamicPrompt = request.dynamicPrompt,
                    imagePath     = request.screenshotPath.takeIf { it.isNotBlank() },
                )
                DbgLog.i(
                    "GemmaModelManager generate done outputChars=${output.length}"
                )
                DbgLog.d("Model raw output preview=${DbgLog.preview(output)}")
                output
            } catch (e: IllegalStateException) {
                when {
                    e.message?.contains("context overflow", ignoreCase = true) == true -> {
                        DbgLog.e("GemmaModelManager context overflow: ${e.message}")
                        throw e  // propagate — AgentOrchestrator handles it as a step error
                    }
                    else -> throw e
                }
            }
        }
    }

    /** Release the active backend and reset state. */
    fun close() {
        DbgLog.i("GemmaModelManager close requested")
        if (!mutex.tryLock()) {
            DbgLog.w("GemmaModelManager close skipped: backend is busy; use suspend release()")
            return
        }
        try {
            activeBackend = null
            _state.value = ModelState.NotInitialized
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Suspend version of [close] — preferred when called from a coroutine.
     * Properly awaits the backend's release() before returning.
     */
    suspend fun release() {
        mutex.withLock {
            DbgLog.i("GemmaModelManager release requested")
            val backend = activeBackend
            activeBackend = null
            _state.value = ModelState.NotInitialized
            backend?.release()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun initializeLocked() {
        if (activeBackend?.isLoaded == true) {
            DbgLog.d("GemmaModelManager initialize skipped: backend already ready")
            return
        }

        val modelFile = getGgufOrLiteRtModelFile() ?: run {
            _state.value = ModelState.MissingAsset
            val msg = "Missing required model file in /sdcard/multiturn/model/"
            DbgLog.e(msg)
            error(msg)
        }

        val config = backendConfig.toLlmConfig()
        val type   = backendConfig.activeBackend

        DbgLog.i(
            "GemmaModelManager initialize backend=$type " +
                "model=${modelFile.absolutePath} config=$config"
        )

        when (type) {
            BackendConfig.BackendType.LITERTLM -> initializeLiteRt(modelFile, config)
            BackendConfig.BackendType.LLAMACPP -> initializeLlamaCpp(modelFile, config)
        }
    }

    private suspend fun initializeLiteRt(modelFile: File, config: LlmConfig) {
        _state.value = ModelState.InitializingGpu
        val backend = LiteRtBackend(context)
        try {
            backend.loadModel(modelFile.absolutePath, config)
            activeBackend = backend
            _state.value = ModelState.Ready("LiteRT", modelFile.absolutePath)
            DbgLog.i("GemmaModelManager LiteRT ready")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "LiteRT init failed: ${e.message}"
            _state.value = ModelState.Error(msg)
            DbgLog.e(msg, e)
            throw IllegalStateException(msg, e)
        }
    }

    private suspend fun initializeLlamaCpp(modelFile: File, config: LlmConfig) {
        _state.value = ModelState.InitializingCpu
        val backend = LlamaCppBackend()
        try {
            backend.loadModel(modelFile.absolutePath, config)
            activeBackend = backend
            // The backend resolves gpuLayers internally (auto-detect probe already done).
            // Read back the effective value to build an informative Ready label.
            val effectiveGpu = backend.config.gpuLayers
            val backendLabel = when {
                effectiveGpu > 0 && backend.isVisionEnabled ->
                    "llama.cpp/GPU+Vision"
                effectiveGpu > 0 ->
                    "llama.cpp/GPU"
                backend.isVisionEnabled ->
                    "llama.cpp/CPU+Vision"
                else ->
                    "llama.cpp/CPU"
            }
            _state.value = ModelState.Ready(backendLabel, modelFile.absolutePath)
            DbgLog.i("GemmaModelManager llama.cpp ready label=$backendLabel gpuLayers=$effectiveGpu")
        } catch (e: IllegalArgumentException) {
            val msg = "llama.cpp: missing or unreadable model: ${e.message}"
            _state.value = ModelState.MissingAsset
            DbgLog.e(msg, e)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            val msg = if (message.contains("native dependency unavailable", ignoreCase = true)) {
                "llama.cpp native dependency unavailable: real llama.cpp sources were not " +
                    "compiled into libllama_jni.so. Add app/src/main/cpp/llama.cpp and rebuild."
            } else {
                "llama.cpp: invalid GGUF or OOM: ${e.message}"
            }
            _state.value = ModelState.Error(msg)
            DbgLog.e(msg, e)
            throw e
        } catch (e: OutOfMemoryError) {
            val msg = "llama.cpp: out of memory loading model"
            _state.value = ModelState.Error(msg)
            DbgLog.e(msg)
            throw e
        }
    }

    /**
     * Finds the model file to load.
     *
     * - For LITERTLM: looks for .bin / .task / .litertlm files (original logic).
     * - For LLAMACPP: looks for .gguf files.
     * - Both backends share the same directory: /sdcard/multiturn/model/
     */
    private fun getGgufOrLiteRtModelFile(): File? = withContext_blocking {
        val modelDir = File(MODEL_DIR)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            DbgLog.e("Model directory not found: ${modelDir.absolutePath}")
            return@withContext_blocking null
        }

        val extensions = when (backendConfig.activeBackend) {
            BackendConfig.BackendType.LITERTLM ->
                setOf(".bin", ".task", ".litertlm")
            BackendConfig.BackendType.LLAMACPP ->
                setOf(".gguf")
        }

        val candidates = modelDir.listFiles { _, name ->
            extensions.any { ext -> name.endsWith(ext, ignoreCase = true) } &&
                !name.contains("mmproj", ignoreCase = true)
        }?.filter { it.canRead() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        val preferredNames = when (backendConfig.activeBackend) {
            BackendConfig.BackendType.LITERTLM ->
                listOf(MODEL_FILE_NAME, WEB_MODEL_FILE_NAME)
            BackendConfig.BackendType.LLAMACPP ->
                listOf(GGUF_MODEL_FILE_NAME)
        }

        val modelFile = preferredNames
            .firstNotNullOfOrNull { preferred ->
                candidates.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
            }
            ?: candidates.firstOrNull()

        if (modelFile == null || !modelFile.exists() || !modelFile.canRead()) {
            DbgLog.e(
                "Readable model file not found in ${modelDir.absolutePath} " +
                    "extensions=$extensions"
            )
            return@withContext_blocking null
        }

        DbgLog.i("Found model file: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
        modelFile
    }

    /**
     * Minimal synchronous wrapper used only in [getGgufOrLiteRtModelFile] which
     * is always called from within a coroutine (via [initialize]).
     * The lambda itself does only File I/O so it is safe on any dispatcher.
     */
    private fun <T> withContext_blocking(block: () -> T): T = block()

    companion object {
        const val MODEL_DIR = "/sdcard/multiturn/model"

        // LiteRT file names (kept for documentation / future reference)
        const val MODEL_FILE_NAME     = "gemma-4-E4B-it.litertlm"
        const val WEB_MODEL_FILE_NAME = "gemma-4-E4B-it-web.litertlm"

        // llama.cpp GGUF file name
        const val GGUF_MODEL_FILE_NAME = "gemma-4-E4B-it-Q4_K_M.gguf"
    }
}
