package com.hemant.plannerv1.model

import com.google.gson.JsonElement
import com.google.gson.JsonNull
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
            val allowedKeys = setOf("thought", "action", "bounding_box", "text", "app_name", "reason", "done")
            val presentKeys = obj.keySet()
            val unknownKeys = presentKeys - allowedKeys
            val missingKeys = allowedKeys - presentKeys
            DbgLog.d(
                "Parser keys=${presentKeys.sorted().joinToString(",")} " +
                    "missing=${missingKeys.sorted().joinToString(",")} " +
                    "unknown=${unknownKeys.sorted().joinToString(",")}",
            )
            require(unknownKeys.isEmpty()) {
                "JSON contains unsupported keys: ${unknownKeys.sorted().joinToString()}."
            }

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
            
            val boundingBox = if (obj.has("bounding_box") && !obj.get("bounding_box").isJsonNull) {
                val array = obj.getAsJsonArray("bounding_box")
                require(array.size() == 4) { "bounding_box must contain exactly 4 numbers." }
                array.map { it.asDouble }
            } else null

            val action = UiAction(
                type = actionType,
                boundingBox = boundingBox,
                text = obj.nullableString("text"),
                appName = obj.nullableString("app_name"),
                reason = obj.requiredString("reason"),
                done = obj.booleanOrDefault("done", defaultValue = actionType == UiActionType.DONE),
            )
            validateActionSchema(action)
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

    private fun validateActionSchema(action: UiAction) {
        require(action.reason.isNotBlank()) { "reason must be non-empty." }
        when (action.type) {
            UiActionType.CLICK -> validateClickAction(action)
            UiActionType.TYPE_TEXT -> validateTypeAction(action)
            UiActionType.SCROLL_UP, UiActionType.SCROLL_DOWN -> validateScrollAction(action)
            UiActionType.OPEN_APP -> validateOpenAppAction(action)
            UiActionType.BACK -> validateBackAction(action)
            UiActionType.WAIT -> { /* No specific validation required */ }
            UiActionType.DONE -> validateDoneAction(action)
        }
    }

    private fun validateClickAction(action: UiAction) {
        require(action.boundingBox != null) { "click requires bounding_box." }
        require(action.text == null) { "click must not include text." }
        require(action.appName == null) { "click must not include app_name." }
        require(!action.done) { "click must set done=false." }
    }

    private fun validateTypeAction(action: UiAction) {
        require(action.boundingBox != null) { "type_text requires bounding_box." }
        require(!action.text.isNullOrBlank()) { "type_text requires non-empty text." }
        require(action.appName == null) { "type_text must not include app_name." }
        require(!action.done) { "type_text must set done=false." }
    }

    private fun validateScrollAction(action: UiAction) {
        require(action.boundingBox == null) { "scroll must not include bounding_box." }
        require(action.text == null) { "scroll must not include text." }
        require(action.appName == null) { "scroll must not include app_name." }
        require(!action.done) { "scroll must set done=false." }
    }

    private fun validateOpenAppAction(action: UiAction) {
        require(action.boundingBox == null) { "open_app must not include bounding_box." }
        require(action.text == null) { "open_app must not include text." }
        require(!action.appName.isNullOrBlank()) { "open_app requires non-empty app_name." }
        require(!action.done) { "open_app must set done=false." }
    }

    private fun validateBackAction(action: UiAction) {
        require(action.boundingBox == null) { "back must not include bounding_box." }
        require(action.text == null) { "back must not include text." }
        require(action.appName == null) { "back must not include app_name." }
        require(!action.done) { "back must set done=false." }
    }

    private fun validateDoneAction(action: UiAction) {
        require(action.boundingBox == null) { "done must not include bounding_box." }
        require(action.text == null) { "done must not include text." }
        require(action.appName == null) { "done must not include app_name." }
        require(action.done) { "done action must set done=true." }
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name)
        require(value != null && !value.isJsonNull && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "$name must be a string."
        }
        return value.asString
    }

    private fun JsonObject.nullableString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string or null." }
        return value.asString
    }

    private fun JsonObject.nullableNumber(name: String): Double? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be a number or null." }
        return value.asDouble
    }

    private fun JsonObject.booleanOrDefault(name: String, defaultValue: Boolean): Boolean {
        val value: JsonElement = get(name) ?: JsonNull.INSTANCE
        if (value.isJsonNull) return defaultValue
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            "$name must be a boolean when present."
        }
        return value.asBoolean
    }
}
