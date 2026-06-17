package com.hemant.plannerv1.model

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class PromptInjectionManager(context: Context) {
    private val prefs = context.getSharedPreferences("prompt_injection_prefs", Context.MODE_PRIVATE)

    private var _isEnabled by mutableStateOf(prefs.getBoolean("is_enabled", false))

    var isEnabled: Boolean
        get() = _isEnabled
        set(value) {
            prefs.edit().putBoolean("is_enabled", value).apply()
            _isEnabled = value
        }

    fun getChecklistForPackage(currentActivity: String): String? {
        if (!isEnabled) return null

        val lowerActivity = currentActivity.lowercase()
        val instructions = when {
            lowerActivity.contains("com.google.android.gm") -> 
                "- Sending an email: click Compose; fill from/sender, to/receiver, subject/title, and body/message."
            lowerActivity.contains("com.whatsapp") -> 
                "- Sending a message: open WhatsApp; go to Chats; find the person; scroll if needed; tap them; type the message in the bottom edit field; click the send arrow."
            lowerActivity.contains("com.microsoft.office.outlook") -> 
                "- Sending an email: click New mail; fill To, Subject, and message body; click Send."
            else -> return null
        }

        return "Common Useful instructions for this app\n${instructions}"
    }
}
