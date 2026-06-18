package com.hemant.plannerv1.eval

/**
 * Captures every measurable field for a single step inside an evaluation run.
 *
 * Token counts are approximations using the chars/4 heuristic (matches OpenAI/Gemini
 * conventions). If the LiteRTLM SDK ever exposes exact token counts on the returned
 * Message object, replace [inputTokensApprox] / [outputTokensApprox] with those values.
 *
 * [modelInferenceLatencyMs] measures ONLY the time spent inside [GemmaModelManager.generate].
 * It excludes screen capture, prompt building, output parsing, UI execution, and settle delays.
 */
data class EvalStepRecord(
    val stepNumber: Int,
    /** Human-readable action label, e.g. "click", "type_text(hello)", "open_app(Settings)". */
    val actionPerformed: String,
    /** The agent's stated reasoning from parsedAction.reason. */
    val reasoning: String,
    /** Approximate input token count: prompt.length / 4. */
    val inputTokensApprox: Int,
    /** Approximate output token count: rawOutput.length / 4. */
    val outputTokensApprox: Int,
    /** Wall-clock time of modelManager.generate() call only, in milliseconds. */
    val modelInferenceLatencyMs: Long,
    /** True only when the agent returned action=done with done=true. */
    val done: Boolean,
    /** Non-null if this step encountered a parse error, repeated action, or execution failure. */
    val error: String?,
)
