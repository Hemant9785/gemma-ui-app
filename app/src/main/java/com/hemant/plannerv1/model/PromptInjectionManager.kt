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

    fun getChecklistForApp(appName: String?, packageName: String?): String? {
        if (!isEnabled) return null

        val workflow = workflowForAppName(appName)
            ?: workflowForPackageName(packageName)
            ?: return null
        val instructions = when (workflow) {

            // ── Gmail ─────────────────────────────────────────────────────────
            AppWorkflow.GMAIL -> """
                APP WORKFLOW — Gmail (follow exactly):

                SENDING AN EMAIL:
                1. If not already in Inbox, tap the hamburger menu (top-left) → tap "Inbox".
                2. Tap the round Compose button (bottom-right corner, red/blue pencil icon).
                3. Tap the "To" field → type the recipient email address → STOP here: typing will auto-submit with Enter.
                4. After To field is filled, tap the "Subject" field → type the subject → STOP again: Enter is pressed automatically.
                5. Tap the large body area below Subject → type the full message text.
                6. Tap the Send button (paper-plane icon, top-right of the compose window).
                7. Once Send is tapped and the compose window closes back to Inbox, output done=true immediately.

                READING / SEARCHING EMAILS:
                - Tap the search bar at the top of Inbox and type your query. After typing, Enter is pressed automatically — call done=true.

                STOP RULE: As soon as the compose window closes (email sent) or the search results appear, output done=true. Do not take any further action after Send.
            """.trimIndent()

            // ── WhatsApp ──────────────────────────────────────────────────────
            AppWorkflow.WHATSAPP -> """
                APP WORKFLOW — WhatsApp (follow exactly):

                SENDING A MESSAGE:
                1. Tap the Chats tab (bottom navigation bar).
                2. Tap the Search icon (magnifying glass, top-right of the chat list) — do NOT scroll to find contacts.
                3. Type the contact name in the search bar → Enter is pressed automatically after typing.
                4. Tap the correct contact from the search results.
                5. Tap the message input field at the bottom of the chat screen.
                6. Type the message text → Enter is pressed automatically after typing.
                7. Output done=true immediately after the message is typed and sent. The send arrow will be triggered by Enter automatically.

                CALLING A NUMBER / CONTACT:
                1. If the goal is to make a voice call: open the specific chat with the contact first (steps 1-4 above).
                2. Once inside the chat, tap the phone icon (top-right header of the chat screen) to start a WhatsApp voice call.
                3. Output done=true as soon as the call screen appears (ringing/connecting).
                - If the goal says "video call": tap the video camera icon instead of the phone icon in the same top-right area.
                - If no chat exists yet: tap the green new-chat pencil icon (bottom-right) → search for the contact → tap them → then tap the phone/video icon at the top.

                STARTING A NEW CHAT (contact not in recent chats):
                1. Tap the green pencil / new-chat icon (bottom-right corner).
                2. Type the contact name in the search field at the top of the contacts list.
                3. Tap the correct contact → proceed to type and send the message.

                STOP RULE: After the message is sent (input field is empty and message appears in chat), or after the call screen appears — output done=true immediately.
            """.trimIndent()

            // ── Microsoft Outlook ─────────────────────────────────────────────
            AppWorkflow.OUTLOOK -> """
                APP WORKFLOW — Outlook (follow exactly):

                SENDING AN EMAIL:
                1. Tap the compose icon (pencil / "+" button, bottom-right corner of the mail list).
                2. Tap the "To" field → type the recipient email address → STOP: Enter is pressed automatically after typing.
                3. Tap the "Add a subject" field → type the subject → STOP: Enter is pressed automatically.
                4. Tap the large message body area → type the full message.
                5. Tap the Send icon (paper-plane icon, top-right toolbar of the compose screen).
                6. Once the compose screen closes and you are back at the mail list, output done=true immediately.

                READING / SEARCHING EMAILS:
                - Tap the search icon (magnifying glass, top-right of mail list) → type your query → Enter is pressed automatically → call done=true once results load.

                STOP RULE: As soon as the email is sent (compose window closed) or search results are visible, output done=true. Do not click anything else.
            """.trimIndent()
        }

        return "\nAPP-SPECIFIC INSTRUCTIONS (treat as highest priority — override general strategy if needed):\n$instructions"
    }

    private fun workflowForAppName(appName: String?): AppWorkflow? {
        val normalized = appName?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank() || normalized == "unknown") return null
        return when {
            normalized.contains("gmail") -> AppWorkflow.GMAIL
            normalized.contains("whatsapp") -> AppWorkflow.WHATSAPP
            normalized.contains("outlook") -> AppWorkflow.OUTLOOK
            else -> null
        }
    }

    private fun workflowForPackageName(packageName: String?): AppWorkflow? {
        val normalized = packageName?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank() || normalized == "unknown") return null
        return when {
            normalized.contains("com.google.android.gm") -> AppWorkflow.GMAIL
            normalized.contains("com.whatsapp") -> AppWorkflow.WHATSAPP
            normalized.contains("com.microsoft.office.outlook") -> AppWorkflow.OUTLOOK
            else -> null
        }
    }
}

private enum class AppWorkflow {
    GMAIL,
    WHATSAPP,
    OUTLOOK,
}
