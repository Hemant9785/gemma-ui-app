package com.hemant.plannerv1.permissions

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.hemant.plannerv1.accessibility.UiActionAccessibilityService

data class PermissionSnapshot(
    val overlayGranted: Boolean,
    val screenCaptureGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val notificationGranted: Boolean,
) {
    val allRuntimeReady: Boolean
        get() = overlayGranted && screenCaptureGranted && accessibilityEnabled && notificationGranted
}

class PermissionManager(private val context: Context) {
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    fun snapshot(screenCaptureGranted: Boolean): PermissionSnapshot {
        return PermissionSnapshot(
            overlayGranted = hasOverlayPermission(),
            screenCaptureGranted = screenCaptureGranted,
            accessibilityEnabled = isAccessibilityEnabled(),
            notificationGranted = hasNotificationPermission(),
        )
    }

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(context, UiActionAccessibilityService::class.java)
            .flattenToString()
            .lowercase()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.lowercase() == expected }
    }

    fun overlaySettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun accessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun screenCaptureIntent(): Intent = projectionManager.createScreenCaptureIntent()

    fun notificationPermission(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    }

    fun shouldRequestNotification(activity: Activity): Boolean {
        val permission = notificationPermission() ?: return false
        return ContextCompat.checkSelfPermission(
            activity,
            permission,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
