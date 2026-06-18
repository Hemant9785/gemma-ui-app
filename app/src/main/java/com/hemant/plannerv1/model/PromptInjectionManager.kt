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

            AppWorkflow.YOUTUBE -> """
                APP WORKFLOW - YouTube (follow exactly):

                SEARCHING / PLAYING:
                1. Tap the Search icon or search field at the top.
                2. Type the requested query. Enter is pressed automatically after typing.
                3. Pick the best matching video/result by checking title, channel, thumbnail, and visible description text.
                4. Ignore ads or sponsored results unless they exactly match the goal.

                PLAYER CONTROLS:
                - If controls are hidden, tap the video/player area once to reveal them.
                - For fullscreen: tap the fullscreen icon in the player controls.
                - For 2x speed: tap settings/gear or three-dot menu, then Playback speed, then 2x.
                - For quality: tap settings/gear or three-dot menu, then Quality. If Advanced is visible, open it and choose the requested or highest suitable quality.
                - If a menu closes or controls disappear, tap the player area again and continue.

                STOP RULE: Output done=true once the requested video is playing or the requested player setting is visibly applied.
            """.trimIndent()

            AppWorkflow.GOOGLE_MAPS -> """
                APP WORKFLOW - Google Maps (follow exactly):

                SEARCH / PLACE:
                1. Tap the search bar at the top and type the requested place/address. Enter is pressed automatically after typing.
                2. Choose the exact place result by matching name, address, category, and visible map/list details.
                3. If multiple similar places appear, prefer the exact name/address match over nearby ads or sponsored suggestions.

                NAVIGATION:
                1. After the correct place is open, tap Directions.
                2. If the goal mentions current location, use the current-location/crosshair button or leave the start field as current location.
                3. If a start location is specified, tap the start field and type that start location.
                4. If transport mode is specified, choose it before Start: car/driving, transit/public transport, walking, bicycle, or ride/taxi.
                5. Tap Start only after destination, start location, and transport mode are correct.
                6. If location permission appears and the goal requires current location/navigation, tap the visible allow option.

                STOP RULE: Output done=true once the place details are open, or once navigation has started when the goal asks to navigate.
            """.trimIndent()

            AppWorkflow.FACEBOOK -> """
                APP WORKFLOW - Facebook (follow exactly):

                COMMON TASKS:
                - Search: tap the Search icon/bar, type the person/page/group/post query, then choose the exact matching result by name, profile image, category, and visible details.
                - Profiles/pages/groups: open the exact match; avoid ads or sponsored results unless truly relevant.
                - Posting: tap "What's on your mind?", type the post text, then tap Post.
                - Marketplace: if the goal is buying/selling/searching items, open Marketplace first if visible, then search and compare item title, image, price, and location.
                - Videos/Reels: use the Video/Reels tab or visible video result when the goal asks to watch video content.
                - Notifications/messages: use the visible notification or message icon only when the goal explicitly asks.

                STOP RULE: Output done=true once the requested profile/page/group/post/item is open, the post is submitted, or the requested Facebook section is visible.
            """.trimIndent()

            AppWorkflow.INSTAGRAM -> """
                APP WORKFLOW - Instagram (follow exactly):

                COMMON TASKS:
                - Search: tap Search/Explore, tap the search field, type the account/topic/place query, then choose the exact matching result.
                - Profiles: prefer exact username first, then display name/profile photo; avoid lookalike accounts.
                - Stories: open the exact profile and tap the avatar/story ring.
                - Reels: use the Reels tab or a visible reel result when the goal asks for reels/video.
                - Posts/media: compare image/video, caption snippet, account, and visible details before opening.
                - Messages: tap the DM/message icon only when the goal asks to message someone.
                - Create/post: tap the create/plus button only when the goal explicitly asks to post or upload.

                STOP RULE: Output done=true once the requested profile, story, reel, post, search result, or message screen is visible.
            """.trimIndent()

            AppWorkflow.PLAY_STORE -> """
                APP WORKFLOW - Google Play Store (follow exactly):

                SEARCHING / INSTALLING APPS:
                1. Tap the search bar at the top.
                2. Type the requested app/game name. Enter is pressed automatically after typing.
                3. Choose the exact app result by checking app name, icon, developer/publisher, rating, and category.
                4. Ignore ads or sponsored results unless they exactly match the requested app.
                5. If the exact result is not visible, scroll down and keep comparing names, icons, and developers.

                INSTALL / OPEN / UPDATE:
                - To install: open the exact app detail page, then tap Install.
                - To open an installed app: tap Open only after confirming the app detail page is the requested app.
                - To update: tap Update only for the requested installed app.
                - If a permission/account/payment prompt appears, do not proceed unless the goal explicitly asks for that step.

                SEARCHING GAMES / APPS BY CATEGORY:
                - For broad goals like "download a puzzle game", compare visible app names, icons, ratings, and categories before choosing.
                - Prefer organic exact matches over sponsored placements.

                STOP RULE: Output done=true once the requested app detail page is open, or once Install/Open/Update has been tapped when the goal asks for that action.
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
            normalized == "youtube" -> AppWorkflow.YOUTUBE
            normalized == "google maps" -> AppWorkflow.GOOGLE_MAPS
            normalized.contains("facebook") -> AppWorkflow.FACEBOOK
            normalized == "instagram" -> AppWorkflow.INSTAGRAM
            normalized == "play store" -> AppWorkflow.PLAY_STORE
            normalized == "google play store" -> AppWorkflow.PLAY_STORE
            normalized.contains("play store") -> AppWorkflow.PLAY_STORE
            normalized.contains("google play") -> AppWorkflow.PLAY_STORE
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
            normalized.contains("com.google.android.youtube") -> AppWorkflow.YOUTUBE
            normalized.contains("com.google.android.apps.maps") -> AppWorkflow.GOOGLE_MAPS
            normalized.contains("com.facebook.katana") -> AppWorkflow.FACEBOOK
            normalized.contains("com.facebook.lite") -> AppWorkflow.FACEBOOK
            normalized.contains("com.instagram.android") -> AppWorkflow.INSTAGRAM
            normalized.contains("com.android.vending") -> AppWorkflow.PLAY_STORE
            else -> null
        }
    }
}

private enum class AppWorkflow {
    GMAIL,
    WHATSAPP,
    OUTLOOK,
    YOUTUBE,
    GOOGLE_MAPS,
    FACEBOOK,
    INSTAGRAM,
    PLAY_STORE,
}
