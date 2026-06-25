package com.hemant.plannerv1.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SpeculativeDecodingManager(context: Context) {
    private val prefs = context.getSharedPreferences("speculative_decoding_prefs", Context.MODE_PRIVATE)

    private var _isEnabled by mutableStateOf(prefs.getBoolean("is_enabled", false))

    var isEnabled: Boolean
        get() = _isEnabled
        set(value) {
            prefs.edit().putBoolean("is_enabled", value).apply()
            _isEnabled = value
        }
}
