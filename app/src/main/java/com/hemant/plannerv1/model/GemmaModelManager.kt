package com.hemant.plannerv1.model

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.hemant.plannerv1.logging.DbgLog
import com.hemant.plannerv1.logging.DbgLog.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.system.measureTimeMillis

sealed interface ModelState {
    data object NotInitialized : ModelState
    data object MissingAsset : ModelState
    data object CopyingAsset : ModelState
    data object InitializingGpu : ModelState
    data object InitializingCpu : ModelState
    data class Ready(val backend: String, val modelPath: String) : ModelState
    data class Error(val message: String) : ModelState
}

class GemmaModelManager(private val context: Context) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelState>(ModelState.NotInitialized)
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var engine: Engine? = null

    suspend fun initialize() {
        mutex.withLock {
            if (engine != null) {
                DbgLog.d("Model initialize skipped: engine already ready")
                return
            }
            DbgLog.i("Model initialize requested")
            val modelFile = copyAssetIfNeeded() ?: run {
                _state.value = ModelState.MissingAsset
                val expected = MODEL_ASSET_CANDIDATES.joinToString { it.assetPath }
                DbgLog.e("Model missing asset. Expected one of: $expected")
                error("Missing required asset. Expected one of: $expected")
            }
            initializeEngine(modelFile)
        }
    }

    suspend fun generate(request: ModelRequest): String {
        initialize()
        val activeEngine = engine ?: error("Gemma engine is not initialized.")
        return withContext(Dispatchers.IO) {
            DbgLog.i(
                "Model inference start screenshot=${request.screenshotPath} " +
                    "promptChars=${request.prompt.length}",
            )
            var output = ""
            val latencyMs = measureTimeMillis {
                output = activeEngine.createConversation(conversationConfig()).use { conversation ->
                    val message = conversation.sendMessage(
                        Contents.of(
                            Content.ImageFile(request.screenshotPath),
                            Content.Text(request.prompt),
                        ),
                    )
                    val text = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString(separator = "") { it.text }
                        .trim()
                    text.ifBlank { message.toString().trim() }
                }
            }
            DbgLog.i("Model inference done latencyMs=$latencyMs outputChars=${output.length}")
            DbgLog.d("Model raw output preview=${output.take(500)}")
            output
        }
    }

    fun close() {
        DbgLog.i("Model close requested")
        engine?.close()
        engine = null
        _state.value = ModelState.NotInitialized
    }

    private suspend fun copyAssetIfNeeded(): File? = withContext(Dispatchers.IO) {
        val asset = findAvailableAsset() ?: return@withContext null
        val outDir = File(context.filesDir, "ui_action_agent/models")
        outDir.mkdirs()
        val outFile = File(outDir, asset.fileName)
        if (outFile.exists() && outFile.length() > 0L) {
            DbgLog.i("Model asset already copied file=${outFile.absolutePath} bytes=${outFile.length()}")
            return@withContext outFile
        }

        _state.value = ModelState.CopyingAsset
        if (!asset.preferred) {
            DbgLog.w(
                "Model using fallback asset ${asset.assetPath}. " +
                    "Preferred Android asset is $ASSET_PATH.",
            )
        }
        DbgLog.i("Model copying asset=${asset.assetPath} to=${outFile.absolutePath}")
        val latencyMs = measureTimeMillis {
            context.assets.open(asset.assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
        }
        DbgLog.i("Model copied asset bytes=${outFile.length()} latencyMs=$latencyMs")
        outFile
    }

    private fun findAvailableAsset(): ModelAsset? {
        return MODEL_ASSET_CANDIDATES.firstOrNull { asset ->
            runCatching {
                context.assets.open(asset.assetPath).close()
                true
            }.getOrDefault(false)
        }?.also {
            DbgLog.i("Model asset found asset=${it.assetPath} preferred=${it.preferred}")
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun initializeEngine(modelFile: File) = withContext(Dispatchers.IO) {
        ExperimentalFlags.enableSpeculativeDecoding = true
        val cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }
        DbgLog.i("Model engine init start path=${modelFile.absolutePath} bytes=${modelFile.length()}")
        val gpuError = runCatching {
            _state.value = ModelState.InitializingGpu
            DbgLog.i("Model engine init GPU start")
            val gpuEngine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    cacheDir = cacheDir.absolutePath,
                ),
            )
            gpuEngine.initialize()
            engine = gpuEngine
            _state.value = ModelState.Ready("GPU", modelFile.absolutePath)
            DbgLog.i("Model engine init GPU success")
        }.exceptionOrNull()

        if (engine != null) return@withContext
        gpuError?.let { DbgLog.w("Model engine init GPU failed: ${it.summary()}", it) }

        runCatching {
            _state.value = ModelState.InitializingCpu
            DbgLog.i("Model engine init CPU start")
            val cpuEngine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    cacheDir = cacheDir.absolutePath,
                ),
            )
            cpuEngine.initialize()
            engine = cpuEngine
            _state.value = ModelState.Ready("CPU", modelFile.absolutePath)
            DbgLog.i("Model engine init CPU success")
        }.getOrElse { cpuError ->
            val message = buildString {
                append("Gemma initialization failed.")
                gpuError?.message?.let { append(" GPU: ").append(it) }
                append(" CPU: ").append(cpuError.message)
            }
            _state.value = ModelState.Error(message)
            DbgLog.e("Model engine init failed: $message", cpuError)
            throw IllegalStateException(message, cpuError)
        }
    }

    private fun conversationConfig(): ConversationConfig {
        return ConversationConfig(
            systemInstruction = Contents.of(
                "You are UIActionAgent. Return only the required JSON action object.",
            ),
            samplerConfig = SamplerConfig(
                topK = 1,
                topP = 0.95,
                temperature = 0.1,
            ),
        )
    }

    companion object {
        const val MODEL_FILE_NAME = "gemma-4-E4B-it.litertlm"
        const val ASSET_PATH = "models/$MODEL_FILE_NAME"
        const val WEB_MODEL_FILE_NAME = "gemma-4-E4B-it-web.litertlm"
        const val WEB_ASSET_PATH = "models/$WEB_MODEL_FILE_NAME"

        private val MODEL_ASSET_CANDIDATES = listOf(
            ModelAsset(ASSET_PATH, MODEL_FILE_NAME, preferred = true),
            ModelAsset(WEB_ASSET_PATH, WEB_MODEL_FILE_NAME, preferred = false),
        )
    }
}

private data class ModelAsset(
    val assetPath: String,
    val fileName: String,
    val preferred: Boolean,
)
