package com.hemant.plannerv1.logging

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hemant.plannerv1.agent.UiAction
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class StepLogEntry(
    val timestamp: String,
    val sessionId: String,
    val goal: String,
    val stepNumber: Int,
    val screenshotPath: String?,
    val prompt: String?,
    val rawModelOutput: String?,
    val parsedAction: UiAction?,
    val executionResult: String?,
    val executionSuccess: Boolean,
    val latencyMs: Long,
    val error: String?,
)

data class StepData(
    val stepNumber: Int,
    val screenshotPath: String?,
    val rawOutput: String?,
    val parsedAction: UiAction?,
    val latencyMs: Long,
    val error: String?,
)

data class EvaluationSession(
    val sessionId: String,
    val goal: String,
    val status: String,
    val steps: Int,
    val averageLatencyMs: Long,
    val invalidJsonCount: Int,
    val stepDataList: List<StepData>,
    val lastError: String?,
    val logPath: String,
)

class TestLogger(private val context: Context) {
    private val gson = Gson()
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(ZoneId.systemDefault())

    fun startSession(goal: String, maxSteps: Int): String {
        val sessionId = "UIActionAgent_${formatter.format(Instant.now())}_${UUID.randomUUID().toString().take(8)}"
        append(
            sessionId,
            mapOf(
                "event" to "session_start",
                "timestamp" to Instant.now().toString(),
                "sessionId" to sessionId,
                "goal" to goal,
                "maxSteps" to maxSteps,
            ),
        )
        return sessionId
    }

    fun logStep(entry: StepLogEntry) {
        append(
            entry.sessionId,
            mapOf(
                "event" to "step",
                "timestamp" to entry.timestamp,
                "sessionId" to entry.sessionId,
                "goal" to entry.goal,
                "stepNumber" to entry.stepNumber,
                "screenshotPath" to entry.screenshotPath,
                "prompt" to entry.prompt,
                "rawModelOutput" to entry.rawModelOutput,
                "parsedAction" to entry.parsedAction,
                "executionResult" to entry.executionResult,
                "executionSuccess" to entry.executionSuccess,
                "latencyMs" to entry.latencyMs,
                "error" to entry.error,
            ),
        )
    }

    fun completeSession(
        sessionId: String,
        status: String,
        invalidJsonCount: Int,
        error: String?,
    ) {
        append(
            sessionId,
            mapOf(
                "event" to "session_end",
                "timestamp" to Instant.now().toString(),
                "sessionId" to sessionId,
                "status" to status,
                "invalidJsonCount" to invalidJsonCount,
                "error" to error,
            ),
        )
    }

    fun loadSessions(): List<EvaluationSession> {
        return logsDir()
            .listFiles { file -> file.extension == "jsonl" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { parseSession(it) }
            ?: emptyList()
    }

    private fun parseSession(file: File): EvaluationSession? {
        var sessionId = file.nameWithoutExtension
        var goal = ""
        var status = "Running/Interrupted"
        var invalidJsonCount = 0
        var lastError: String? = null
        val latencies = mutableListOf<Long>()
        val stepsMap = mutableMapOf<Int, StepData>()

        file.forEachLine { line ->
            val obj = runCatching {
                JsonParser.parseString(line).asJsonObject
            }.getOrNull() ?: return@forEachLine
            when (obj.stringOrNull("event")) {
                "session_start" -> {
                    sessionId = obj.stringOrNull("sessionId") ?: sessionId
                    goal = obj.stringOrNull("goal").orEmpty()
                }
                "step" -> {
                    val stepNum = obj.intOrNull("stepNumber") ?: 0
                    val latency = obj.longOrNull("latencyMs") ?: 0L
                    if (latency > 0) latencies += latency
                    
                    val actionStr = obj.getAsJsonObjectOrNull("parsedAction")?.toString()
                    val parsedAction = runCatching {
                        if (actionStr != null) gson.fromJson(actionStr, UiAction::class.java) else null
                    }.getOrNull()
                    
                    val err = obj.stringOrNull("error")
                    if (err != null) lastError = err
                    
                    stepsMap[stepNum] = StepData(
                        stepNumber = stepNum,
                        screenshotPath = obj.stringOrNull("screenshotPath"),
                        rawOutput = obj.stringOrNull("rawModelOutput"),
                        parsedAction = parsedAction,
                        latencyMs = latency,
                        error = err,
                    )
                }
                "session_end" -> {
                    status = obj.stringOrNull("status") ?: status
                    invalidJsonCount = obj.intOrNull("invalidJsonCount") ?: invalidJsonCount
                    lastError = obj.stringOrNull("error") ?: lastError
                }
            }
        }
        val stepDataList = stepsMap.toSortedMap().values.toList()
        return EvaluationSession(
            sessionId = sessionId,
            goal = goal,
            status = status,
            steps = latencies.size,
            averageLatencyMs = if (latencies.isEmpty()) 0 else latencies.average().toLong(),
            invalidJsonCount = invalidJsonCount,
            stepDataList = stepDataList,
            lastError = lastError,
            logPath = file.absolutePath,
        )
    }

    private fun append(sessionId: String, value: Any) {
        val file = File(logsDir(), "$sessionId.jsonl")
        file.appendText(gson.toJson(value) + "\n")
    }

    private fun logsDir(): File {
        return File(context.filesDir, "ui_action_agent/logs").apply { mkdirs() }
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asString
    }

    private fun JsonObject.longOrNull(name: String): Long? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asLong
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asInt
    }

    private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonObject) return null
        return value.asJsonObject
    }
}
