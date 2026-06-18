package com.hemant.plannerv1.eval

import com.hemant.plannerv1.logging.DbgLog
import java.io.File

/**
 * Reads a plain-text goals file where each non-blank, non-comment line is one goal.
 * Lines starting with '#' are treated as comments and skipped.
 */
object EvalGoalReader {

    /**
     * Parses [file] and returns a list of trimmed goal strings.
     * @throws IllegalArgumentException if the file does not exist or yields no goals.
     */
    fun readGoals(file: File): List<String> {
        require(file.exists()) { "Goals file not found: ${file.absolutePath}" }
        require(file.canRead()) { "Goals file is not readable: ${file.absolutePath}" }

        val goals = file.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

        DbgLog.i("EvalGoalReader loaded ${goals.size} goals from ${file.absolutePath}")
        goals.forEachIndexed { i, g -> DbgLog.d("  Goal ${i + 1}: $g") }

        require(goals.isNotEmpty()) {
            "Goals file contains no valid goals (all lines blank or commented): ${file.absolutePath}"
        }

        return goals
    }
}
