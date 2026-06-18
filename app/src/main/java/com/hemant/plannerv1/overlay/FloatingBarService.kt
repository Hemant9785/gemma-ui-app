package com.hemant.plannerv1.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hemant.plannerv1.AppContainer
import com.hemant.plannerv1.R
import com.hemant.plannerv1.logging.DbgLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class FloatingBarService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val overlayViewModelStore = ViewModelStore()
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var markerOverlayView: ComposeView? = null

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = overlayViewModelStore

    override fun onCreate() {
        super.onCreate()
        DbgLog.i("FloatingBarService onCreate - starting")
        activeInstance = this
        DbgLog.d("FloatingBarService performing SavedStateRegistry restore")
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        DbgLog.d("FloatingBarService initializing AppContainer")
        AppContainer.initialize(applicationContext)
        DbgLog.d("FloatingBarService getting WindowManager")
        windowManager = getSystemService(WindowManager::class.java)
        DbgLog.d("FloatingBarService creating channel")
        createChannel()
        DbgLog.d("FloatingBarService starting foreground")
        startTypedForeground(notification())
        DbgLog.d("FloatingBarService adding marker overlay")
        addMarkerOverlay()
        DbgLog.d("FloatingBarService adding overlay")
        addOverlay()
        DbgLog.i("FloatingBarService onCreate - completed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        DbgLog.d("FloatingBarService onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun startTypedForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        DbgLog.i("FloatingBarService onDestroy")
        if (activeInstance === this) {
            activeInstance = null
        }
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        markerOverlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        markerOverlayView = null
        markerState.value = null
        overlayViewModelStore.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun addMarkerOverlay() {
        if (!AppContainer.permissionManager.hasOverlayPermission()) {
            DbgLog.w("FloatingBarService marker overlay skipped reason=no_overlay_permission")
            return
        }
        if (markerOverlayView != null) return

        val view = ComposeView(this).apply {
            DbgLog.d("FloatingBarService applying ComposeView properties for marker overlay")
            setViewTreeLifecycleOwner(this@FloatingBarService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBarService)
            setViewTreeViewModelStoreOwner(this@FloatingBarService)
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                val marker by markerState.asStateFlow().collectAsState()
                Box(modifier = Modifier.fillMaxSize()) {
                    val currentMarker = marker
                    if (currentMarker != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            if (currentMarker.bounds != null) {
                                drawRect(
                                    color = androidx.compose.ui.graphics.Color.Red,
                                    topLeft = Offset(currentMarker.bounds.left.toFloat(), currentMarker.bounds.top.toFloat()),
                                    size = androidx.compose.ui.geometry.Size(
                                        currentMarker.bounds.width().toFloat(),
                                        currentMarker.bounds.height().toFloat()
                                    ),
                                    style = Stroke(width = 8.dp.toPx())
                                )
                            } else if (currentMarker.x != null && currentMarker.y != null) {
                                drawCircle(
                                    color = androidx.compose.ui.graphics.Color.Red,
                                    radius = 36.dp.toPx(),
                                    center = Offset(currentMarker.x.toFloat(), currentMarker.y.toFloat()),
                                    style = Stroke(width = 8.dp.toPx()),
                                )
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 96.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Surface(
                                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = currentMarker.text,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        windowManager.addView(view, params)
        markerOverlayView = view
    }

    private fun addOverlay() {
        if (!AppContainer.permissionManager.hasOverlayPermission()) {
            stopSelf()
            return
        }
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val view = ComposeView(this).apply {
            DbgLog.d("FloatingBarService applying ComposeView properties for main overlay")
            setViewTreeLifecycleOwner(this@FloatingBarService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBarService)
            setViewTreeViewModelStoreOwner(this@FloatingBarService)
            setContent {
                FloatingBarView(
                    onClose = {
                        DbgLog.d("FloatingBarService close button clicked")
                        AppContainer.agentOrchestrator.stop()
                        stopSelf()
                    },
                    onDrag = { dx, dy ->
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        windowManager.updateViewLayout(this@apply, params)
                    }
                )
            }
            
            this@FloatingBarService.lifecycleScope.launch {
                launch {
                    AppContainer.agentOrchestrator.state.collect {
                        updateFocusableFlag(params, this@apply)
                    }
                }
                launch {
                    AppContainer.evalRunner.state.collect {
                        updateFocusableFlag(params, this@apply)
                    }
                }
            }
        }
        windowManager.addView(view, params)
        overlayView = view
    }

    private fun updateFocusableFlag(params: WindowManager.LayoutParams, view: View) {
        val isRunning = AppContainer.agentOrchestrator.state.value.isRunning ||
                AppContainer.evalRunner.state.value.isRunning
        val hasFocusableFlag = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        
        if (isRunning && !hasFocusableFlag) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
            }
            DbgLog.d("FloatingBarService added FLAG_NOT_FOCUSABLE")
        } else if (!isRunning && hasFocusableFlag) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
            }
            DbgLog.d("FloatingBarService removed FLAG_NOT_FOCUSABLE")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "UIActionAgent floating bar",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("UIActionAgent")
            .setContentText("Floating research controller is active.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.hemant.plannerv1.overlay.START"
        const val ACTION_STOP = "com.hemant.plannerv1.overlay.STOP"
        private const val CHANNEL_ID = "ui_action_agent_overlay"
        private const val NOTIFICATION_ID = 2200
        private val mainHandler = Handler(Looper.getMainLooper())
        private val markerState = MutableStateFlow<ActionMarker?>(null)

        @Volatile
        private var activeInstance: FloatingBarService? = null

        fun startIntent(context: Context): Intent {
            return Intent(context, FloatingBarService::class.java).setAction(ACTION_START)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, FloatingBarService::class.java).setAction(ACTION_STOP)
        }

        fun setCaptureVisibility(hidden: Boolean) {
            val service = activeInstance
            if (service == null) {
                DbgLog.d("FloatingBarService visibility change skipped hidden=$hidden reason=no_active_instance")
                return
            }
            mainHandler.post {
                service.updateOverlayVisibility(hidden)
            }
        }

        fun showActionMarker(text: String, x: Int? = null, y: Int? = null, bounds: Rect? = null) {
            mainHandler.post {
                markerState.value = ActionMarker(x = x, y = y, bounds = bounds, text = text)
                DbgLog.i("FloatingBarService action marker show text=$text")
            }
        }

        fun hideActionMarker() {
            mainHandler.post {
                markerState.value = null
                DbgLog.d("FloatingBarService action marker hide")
            }
        }
    }

    private fun updateOverlayVisibility(hidden: Boolean) {
        val view = overlayView
        if (view == null) {
            DbgLog.d("FloatingBarService visibility change skipped hidden=$hidden reason=no_overlay_view")
            return
        }
        val newVisibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (view.visibility == newVisibility) {
            DbgLog.d("FloatingBarService visibility unchanged hidden=$hidden")
            return
        }
        view.visibility = newVisibility
        DbgLog.i("FloatingBarService overlay visibility hidden=$hidden")
    }
}

private data class ActionMarker(
    val x: Int?,
    val y: Int?,
    val bounds: Rect?,
    val text: String
)
