package com.hemant.plannerv1.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.hemant.plannerv1.logging.DbgLog
import com.hemant.plannerv1.logging.DbgLog.summary
import com.hemant.plannerv1.overlay.FloatingBarService
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

class ScreenCaptureManager(
    private val context: Context,
    private val processor: ScreenshotProcessor = ScreenshotProcessor(context),
) {
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val mutex = Mutex()

    private var resultCode: Int? = null
    private var resultData: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var releasingProjection = false

    val isReady: Boolean
        get() = resultCode != null && resultData != null

    fun setProjectionPermission(resultCode: Int, data: Intent?) {
        DbgLog.i("Capture permission received resultCode=$resultCode hasData=${data != null}")
        releaseProjection(clearPermission = false)
        this.resultCode = resultCode
        this.resultData = data
    }

    suspend fun capture(sessionId: String, stepNumber: Int): ScreenshotFrame = mutex.withLock {
        DbgLog.i("Capture start session=$sessionId step=$stepNumber ready=$isReady")
        runCatching {
            try {
                val reader = ensureReader()
                drainImages(reader) // Clear out stale frames
                hideFloatingBarForCapture() // Hide UI, which triggers a fresh frame
                
                // Wait briefly for the frame to arrive if the hide was too fast
                delay(100)
                
                val image = acquireLatestImage(reader)
                    ?: error("Screen capture produced no image. Try granting screen capture again.")
                val bitmap = image.useToBitmap()
                try {
                    processor.process(sessionId, stepNumber, bitmap).also { frame ->
                        DbgLog.i(
                            "Capture success original=${frame.originalWidth}x${frame.originalHeight} " +
                                "model=${frame.modelWidth}x${frame.modelHeight} path=${frame.originalPath}",
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                showFloatingBarAfterCapture()
            }
        }.getOrElse { throwable ->
            DbgLog.e("Capture failed: ${throwable.summary()}", throwable)
            if (throwable.message?.contains("MediaProjection#createVirtualDisplay") == true ||
                throwable.message?.contains("resultData") == true ||
                throwable.message?.contains("token") == true
            ) {
                invalidatePermission("MediaProjection token was rejected")
                throw IllegalStateException(
                    "Screen capture permission expired. Tap Capture again, then rerun the command.",
                    throwable,
                )
            }
            throw throwable
        }
    }

    fun releaseProjection(clearPermission: Boolean = false) {
        if (releasingProjection) return
        releasingProjection = true
        try {
            DbgLog.d("Capture releaseProjection clearPermission=$clearPermission")
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            projectionCallback?.let { callback ->
                mediaProjection?.unregisterCallback(callback)
            }
            projectionCallback = null
            mediaProjection?.stop()
            mediaProjection = null
            if (clearPermission) {
                resultCode = null
                resultData = null
                DbgLog.w("Capture permission invalidated")
            }
        } finally {
            releasingProjection = false
        }
    }

    private fun ensureReader(): ImageReader {
        val projection = ensureProjection()
        val metrics = currentDisplayMetrics()
        val existing = imageReader
        if (existing != null && virtualDisplay != null) {
            DbgLog.d("Capture using existing ImageReader/VirtualDisplay")
            return existing
        }

        DbgLog.i(
            "Capture creating ImageReader/VirtualDisplay " +
                "${metrics.widthPixels}x${metrics.heightPixels} dpi=${metrics.densityDpi}",
        )
        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader = reader
        virtualDisplay = runCatching {
            projection.createVirtualDisplay(
                "UIActionAgentCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
        }.getOrElse { throwable ->
            DbgLog.e("Capture createVirtualDisplay failed: ${throwable.summary()}", throwable)
            reader.close()
            imageReader = null
            invalidatePermission("createVirtualDisplay failed")
            throw throwable
        }
        return reader
    }

    private fun ensureProjection(): MediaProjection {
        mediaProjection?.let { return it }
        val code = resultCode ?: error("Screen capture permission has not been granted.")
        val data = resultData ?: error("Screen capture permission data is missing.")
        DbgLog.i("Capture creating MediaProjection from permission data")
        val projection = runCatching {
            projectionManager.getMediaProjection(code, data)
        }.getOrElse { throwable ->
            DbgLog.e("Capture getMediaProjection failed: ${throwable.summary()}", throwable)
            invalidatePermission("getMediaProjection failed")
            throw IllegalStateException(
                "Screen capture permission expired. Tap Capture again, then rerun the command.",
                throwable,
            )
        } ?: error("Unable to create MediaProjection from permission data.")
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                DbgLog.w("Capture MediaProjection onStop callback")
                releaseProjection(clearPermission = true)
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback
        mediaProjection = projection
        DbgLog.i("Capture MediaProjection ready")
        return projection
    }

    private fun invalidatePermission(reason: String) {
        DbgLog.w("Capture invalidatePermission reason=$reason")
        releaseProjection(clearPermission = true)
    }

    private suspend fun hideFloatingBarForCapture() {
        DbgLog.d("Capture hiding floating bar before screenshot")
        FloatingBarService.setCaptureVisibility(hidden = true)
        delay(150)
    }

    private suspend fun showFloatingBarAfterCapture() {
        DbgLog.d("Capture restoring floating bar after screenshot")
        FloatingBarService.setCaptureVisibility(hidden = false)
        delay(75)
    }

    private fun currentDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun drainImages(reader: ImageReader) {
        while (true) {
            val image = reader.acquireLatestImage() ?: return
            image.close()
        }
    }

    private fun acquireLatestImage(reader: ImageReader): Image? {
        repeat(5) {
            reader.acquireLatestImage()?.let { return it }
            Thread.sleep(100)
        }
        return reader.acquireLatestImage()
    }

    private fun Image.useToBitmap(): Bitmap {
        use { image ->
            val plane = image.planes.first()
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
            paddedBitmap.recycle()
            return cropped
        }
    }
}
