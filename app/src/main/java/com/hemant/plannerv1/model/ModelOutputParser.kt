package com.hemant.plannerv1.model

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hemant.plannerv1.agent.SwipeDirection
import com.hemant.plannerv1.agent.UiAction
import com.hemant.plannerv1.agent.UiActionType

class ModelOutputParser {
    fun parse(rawOutput: String): UiAction {
        val trimmed = rawOutput.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "Model output must be a single JSON object with no surrounding text."
        }
        val element = runCatching { JsonParser.parseString(trimmed) }
            .getOrElse { throw IllegalArgumentException("Invalid JSON: ${it.message}") }
        require(element.isJsonObject) { "Model output must be a JSON object." }
        val obj = element.asJsonObject
        val requiredKeys = setOf("action", "x", "y", "text", "direction", "reason", "done")
        require(obj.keySet() == requiredKeys) {
            "JSON keys must exactly match ${requiredKeys.joinToString()}."
        }

        val actionType = when (obj.requiredString("action")) {
            "click" -> UiActionType.CLICK
            "type" -> UiActionType.TYPE
            "swipe" -> UiActionType.SWIPE
            "back" -> UiActionType.BACK
            "done" -> UiActionType.DONE
            else -> throw IllegalArgumentException("Unsupported action.")
        }
        val direction = obj.nullableString("direction")?.let { value ->
            SwipeDirection.entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unsupported swipe direction.")
        }
        val action = UiAction(
            type = actionType,
            x = obj.nullableNumber("x"),
            y = obj.nullableNumber("y"),
            text = obj.nullableString("text"),
            direction = direction,
            reason = obj.requiredString("reason"),
            done = obj.requiredBoolean("done"),
        )
        validateByAction(action)
        return action
    }

    private fun validateByAction(action: UiAction) {
        require(action.reason.isNotBlank()) { "reason must be non-empty." }
        when (action.type) {
            UiActionType.CLICK -> {
                require(action.x != null && action.y != null) { "click requires x and y." }
                require(action.x >= 0.0 && action.y >= 0.0) { "click coordinates must be non-negative." }
                require(action.text == null && action.direction == null) {
                    "click must not include text or direction."
                }
                require(!action.done) { "click must set done=false." }
            }
            UiActionType.TYPE -> {
                require(!action.text.isNullOrEmpty()) { "type requires text." }
                require(action.x == null && action.y == null && action.direction == null) {
                    "type must not include coordinates or direction."
                }
                require(!action.done) { "type must set done=false." }
            }
            UiActionType.SWIPE -> {
                require(action.direction != null) { "swipe requires direction." }
                require(action.x == null && action.y == null && action.text == null) {
                    "swipe must not include coordinates or text."
                }
                require(!action.done) { "swipe must set done=false." }
            }
            UiActionType.BACK -> {
                require(action.x == null && action.y == null && action.text == null && action.direction == null) {
                    "back must not include coordinates, text, or direction."
                }
                require(!action.done) { "back must set done=false." }
            }
            UiActionType.DONE -> {
                require(action.x == null && action.y == null && action.text == null && action.direction == null) {
                    "done must not include coordinates, text, or direction."
                }
                require(action.done) { "done action must set done=true." }
            }
        }
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

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val value: JsonElement = get(name) ?: JsonNull.INSTANCE
        require(!value.isJsonNull && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            "$name must be a boolean."
        }
        return value.asBoolean
    }
}
