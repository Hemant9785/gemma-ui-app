package com.hemant.plannerv1.logging

import android.util.Log

object DbgLog {
    const val TAG = "HEMANT_DBG"

    fun d(message: String, tag: String = TAG) {
        Log.d(tag, message)
    }

    fun i(message: String, tag: String = TAG) {
        Log.i(tag, message)
    }

    fun w(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (throwable == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, throwable)
        }
    }

    fun e(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
    }

    fun preview(value: String?, maxChars: Int = 400): String {
        if (value.isNullOrEmpty()) return "<empty>"
        val normalized = value.replace("\r", "\\r").replace("\n", "\\n")
        return if (normalized.length <= maxChars) {
            normalized
        } else {
            normalized.take(maxChars) + "...(truncated)"
        }
    }

    fun Throwable.summary(): String {
        return "${this::class.java.simpleName}: ${message.orEmpty()}"
    }
}
