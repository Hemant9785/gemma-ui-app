package com.hemant.plannerv1.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hemant.plannerv1.agent.UiAction
import com.hemant.plannerv1.agent.UiActionType
import com.hemant.plannerv1.logging.DbgLog
import com.hemant.plannerv1.logging.DbgLog.preview
import com.hemant.plannerv1.logging.DbgLog.summary

class ModelOutputParser {
    fun parse(rawOutput: String): UiAction {
        val trimmed = rawOutput.trim()
        val normalized = stripThoughtChannel(trimmed)
        val firstChar = trimmed.firstOrNull()?.toString() ?: "<empty>"
        val lastChar = trimmed.lastOrNull()?.toString() ?: "<empty>"
        val hasMarkdownFence = trimmed.contains("```")
        val thoughtChannelDetected = trimmed != normalized
        DbgLog.d(
            "Parser start rawChars=${rawOutput.length} trimmedChars=${trimmed.length} " +
                "normalizedChars=${normalized.length} firstChar=$firstChar lastChar=$lastChar " +
                "hasMarkdownFence=$hasMarkdownFence thoughtChannelDetected=$thoughtChannelDetected " +
                "preview=${preview(rawOutput)}",
        )
        return try {
            require(normalized.startsWith("{") && normalized.endsWith("}")) {
                "Model output must be a single JSON object with no surrounding text."
            }
            val element = runCatching { JsonParser.parseString(normalized) }
                .getOrElse { throw IllegalArgumentException("Invalid JSON: ${it.message}") }
            require(element.isJsonObject) { "Model output must be a JSON object." }
            val obj = element.asJsonObject
            val presentKeys = obj.keySet()
            DbgLog.d(
                "Parser keys=${presentKeys.sorted().joinToString(",")}",
            )

            val actionType = when (obj.requiredString("action")) {
                "click" -> UiActionType.CLICK
                "type_text" -> UiActionType.TYPE_TEXT
                "scroll_up" -> UiActionType.SCROLL_UP
                "scroll_down" -> UiActionType.SCROLL_DOWN
                "open_app" -> UiActionType.OPEN_APP
                "wait" -> UiActionType.WAIT
                "back" -> UiActionType.BACK
                "done" -> UiActionType.DONE
                else -> throw IllegalArgumentException("Unsupported action.")
            }

            val boundingBox = when (actionType) {
                UiActionType.CLICK, UiActionType.TYPE_TEXT -> obj.requiredBoundingBox()
                else -> null
            }
            val text = when (actionType) {
                UiActionType.TYPE_TEXT -> obj.requiredNonBlankString("text")
                else -> null
            }
            val appName = when (actionType) {
                UiActionType.OPEN_APP -> obj.requiredNonBlankString("app_name")
                else -> null
            }

            val action = UiAction(
                type = actionType,
                boundingBox = boundingBox,
                text = text,
                appName = appName,
                reason = obj.optionalString("reason").orEmpty(),
                done = actionType == UiActionType.DONE,
            )
            DbgLog.i(
                "Parser success action=${action.type.value} done=${action.done} " +
                    "reasonChars=${action.reason.length}",
            )
            action
        } catch (throwable: Throwable) {
            DbgLog.w(
                "Parser failed rawChars=${rawOutput.length} trimmedChars=${trimmed.length} " +
                    "normalizedChars=${normalized.length} firstChar=$firstChar lastChar=$lastChar " +
                    "hasMarkdownFence=$hasMarkdownFence thoughtChannelDetected=$thoughtChannelDetected " +
                    "preview=${preview(rawOutput)} normalizedPreview=${preview(normalized)} " +
                    "error=${throwable.summary()}",
                throwable,
            )
            throw throwable
        }
    }

    private fun stripThoughtChannel(value: String): String {
        val pattern = Regex("""(?s)<\|channel>thought.*?<channel\|>""")
        val normalized = value.replace(pattern, "").trim()
        if (normalized != value) {
            DbgLog.d(
                "Parser stripped thought channel removedChars=${value.length - normalized.length} " +
                    "normalizedPreview=${preview(normalized)}",
            )
        }
        return normalized
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name)
        require(value != null && !value.isJsonNull && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "$name must be a string."
        }
        return value.asString
    }

    private fun JsonObject.requiredNonBlankString(name: String): String {
        val value = requiredString(name)
        require(value.isNotBlank()) { "$name must be non-empty." }
        return value
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.requiredBoundingBox(): List<Double> {
        val value = get("bounding_box")
        require(value != null && !value.isJsonNull && value.isJsonArray) {
            "bounding_box must be an array of 4 numbers."
        }
        val array = value.asJsonArray
        require(array.size() == 4) { "bounding_box must contain exactly 4 numbers." }
        val coordinates = array.map { coordinate ->
            require(coordinate.isJsonPrimitive && coordinate.asJsonPrimitive.isNumber) {
                "bounding_box must contain exactly 4 numbers."
            }
            coordinate.asDouble.also { number ->
                require(number.isFinite() && number in 0.0..1000.0) {
                    "bounding_box coordinates must be finite numbers in the 0-1000 range."
                }
            }
        }
        require(coordinates[0] <= coordinates[2] && coordinates[1] <= coordinates[3]) {
            "bounding_box must use [ymin, xmin, ymax, xmax] ordering."
        }
        return coordinates
    }
}
