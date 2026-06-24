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
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * LiteRT-LM backend — wraps the original [Engine] inference logic.
 *
 * [staticPrefix] and [dynamicPrompt] are joined into the single user message sent
 * to the conversation. This keeps LiteRT behavior aligned with the full prompt
 * used by the llama.cpp backend.
 *
 * For multimodal inference the caller must set [screenshotPath] on the [ModelRequest]
 * before calling [generate]. This backend does not use [LlmConfig] sampler fields —
 * it applies its own [SamplerConfig] hardcoded for JSON output (greedy / topK=1).
 */
class LiteRtBackend(private val context: Context) : LlmBackend {

    private var engine: Engine? = null

    override val isLoaded: Boolean get() = engine != null

    @OptIn(ExperimentalApi::class)
    override suspend fun loadModel(modelPath: String, config: LlmConfig) {
        if (engine != null) {
            DbgLog.d("LiteRtBackend loadModel skipped: already loaded")
            return
        }
        withContext(Dispatchers.IO) {
            ExperimentalFlags.enableSpeculativeDecoding = false
            val cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }
            DbgLog.i("LiteRtBackend loadModel start path=$modelPath")
            val loadMs = measureTimeMillis {
                val gpuError = runCatching {
                    val gpuEngine = Engine(
                        EngineConfig(
                            modelPath    = modelPath,
                            backend      = Backend.GPU(),
                            visionBackend = Backend.CPU(),
                            cacheDir     = cacheDir.absolutePath,
                        ),
                    )
                    gpuEngine.initialize()
                    engine = gpuEngine
                    DbgLog.i("LiteRtBackend GPU init success")
                }.exceptionOrNull()

                if (engine == null) {
                    gpuError?.let {
                        DbgLog.w("LiteRtBackend GPU init failed: ${it.summary()}", it)
                    }
                    val cpuEngine = Engine(
                        EngineConfig(
                            modelPath    = modelPath,
                            backend      = Backend.CPU(),
                            visionBackend = Backend.CPU(),
                            cacheDir     = cacheDir.absolutePath,
                        ),
                    )
                    cpuEngine.initialize()
                    engine = cpuEngine
                    DbgLog.i("LiteRtBackend CPU init success")
                }
            }
            DbgLog.i("LiteRtBackend loadModel done loadMs=$loadMs")
        }
    }

    override suspend fun generate(staticPrefix: String, dynamicPrompt: String, imagePath: String?): String {
        val activeEngine = engine ?: error("LiteRtBackend: engine not loaded")
        return withContext(Dispatchers.IO) {
            var output = ""
            val latencyMs = measureTimeMillis {
                output = activeEngine.createConversation(conversationConfig()).use { conv ->
                    val fullPrompt = buildString {
                        append(staticPrefix.trim())
                        append("\n")
                        append(dynamicPrompt.trim())
                    }
                    val imageContent = imagePath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Content.ImageFile(it) }
                    val message = if (imageContent != null) {
                        conv.sendMessage(
                            Contents.of(imageContent, Content.Text(fullPrompt)),
                        )
                    } else {
                        conv.sendMessage(Contents.of(Content.Text(fullPrompt)))
                    }
                    val text = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString(separator = "") { it.text }
                        .trim()
                    text.ifBlank { message.toString().trim() }
                }
            }
            DbgLog.i("LiteRtBackend generate done latencyMs=$latencyMs outputChars=${output.length}")
            output
        }
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) {
            DbgLog.i("LiteRtBackend release")
            engine?.close()
            engine = null
        }
    }

    // ── LiteRT-specific helpers ───────────────────────────────────────────────

    private fun conversationConfig(): ConversationConfig {
        return ConversationConfig(
            systemInstruction = Contents.of(
                "<|think|>" +
                    "You are UIActionAgent, an on-device Android UI automation agent. " +
                    "You MUST strictly follow these rules:\n" +
                    "1. If on the Home Screen / Launcher and the goal requires opening an app, use 'open_app' action. Do NOT click app icons visually.\n" +
                    "2. Prefer high-level actions over visual clicks.\n" +
                    "3. Return only the required JSON action object in the final answer.",
            ),
            samplerConfig = SamplerConfig(
                topK        = 1,
                topP        = 0.95,
                temperature = 0.0,
            ),
        )
    }
}
