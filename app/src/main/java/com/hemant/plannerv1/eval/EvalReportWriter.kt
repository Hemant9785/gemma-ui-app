package com.hemant.plannerv1.eval

import com.google.gson.GsonBuilder
import com.hemant.plannerv1.logging.DbgLog
import java.io.File
import java.time.Instant

/**
 * Serialises a list of [EvalGoalResult] to:
 *  - `report.json`  — machine-readable structured JSON
 *  - `reports.txt`  — human-readable plain-text report
 *
 * Both methods are synchronous (call from a background coroutine).
 */
object EvalReportWriter {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ── JSON ───────────────────────────────────────────────────────────────

    fun writeJson(results: List<EvalGoalResult>, outFile: File) {
        val root = mapOf(
            "generated_at" to Instant.now().toString(),
            "total_goals" to results.size,
            "successful_goals" to results.count { it.status == "Success" },
            "goals" to results.map { goalToMap(it) },
        )
        outFile.writeText(gson.toJson(root))
        DbgLog.i("EvalReportWriter wrote JSON → ${outFile.absolutePath}")
    }

    private fun goalToMap(r: EvalGoalResult): Map<String, Any?> = mapOf(
        "goal_number" to r.goalNumber,
        "goal" to r.goal,
        "status" to r.status,
        "total_steps" to r.totalSteps,
        "avg_inference_ms" to r.avgInferenceMs,
        "steps" to r.steps.map { stepToMap(it) },
    )

    private fun stepToMap(s: EvalStepRecord): Map<String, Any?> = mapOf(
        "step_number" to s.stepNumber,
        "action_performed" to s.actionPerformed,
        "reasoning" to s.reasoning,
        "input_tokens_approx" to s.inputTokensApprox,
        "output_tokens_approx" to s.outputTokensApprox,
        "model_inference_latency_ms" to s.modelInferenceLatencyMs,
        "done" to s.done,
        "error" to s.error,
    )

    // ── Plain Text ─────────────────────────────────────────────────────────

    fun writeTxt(results: List<EvalGoalResult>, outFile: File) {
        val sb = StringBuilder()
        val divider = "═".repeat(52)
        val subDivider = "─".repeat(52)

        sb.appendLine("UI AGENT BENCHMARK REPORT")
        sb.appendLine("Generated : ${Instant.now()}")
        sb.appendLine("Total Goals: ${results.size}  |  " +
                "Successful: ${results.count { it.status == "Success" }}  |  " +
                "Failed: ${results.count { it.status != "Success" }}")
        sb.appendLine()

        for (r in results) {
            sb.appendLine(divider)
            sb.appendLine("Goal #${r.goalNumber}: ${r.goal}")
            sb.appendLine("Status      : ${r.status}")
            sb.appendLine("Total Steps : ${r.totalSteps}")
            sb.appendLine("Avg Inference: ${r.avgInferenceMs} ms  (model only)")
            sb.appendLine(subDivider)

            if (r.steps.isEmpty()) {
                sb.appendLine("  (no steps recorded)")
            } else {
                for (s in r.steps) {
                    sb.appendLine("  Step ${s.stepNumber}: ${s.actionPerformed}")
                    sb.appendLine("    Reason  : ${s.reasoning}")
                    sb.appendLine("    Tokens  : in≈${s.inputTokensApprox}  out≈${s.outputTokensApprox}  (approx, chars/4)")
                    sb.appendLine("    Latency : ${s.modelInferenceLatencyMs} ms (model inference only)")
                    sb.appendLine("    Done    : ${s.done}")
                    if (s.error != null) {
                        sb.appendLine("    ERROR   : ${s.error}")
                    }
                    sb.appendLine()
                }
            }
        }

        sb.appendLine(divider)
        outFile.writeText(sb.toString())
        DbgLog.i("EvalReportWriter wrote TXT → ${outFile.absolutePath}")
    }
}
