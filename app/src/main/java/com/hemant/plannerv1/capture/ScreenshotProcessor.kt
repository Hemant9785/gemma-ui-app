package com.hemant.plannerv1.capture

import android.content.Context
import android.graphics.Bitmap
import com.hemant.plannerv1.logging.DbgLog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class ScreenshotFrame(
    val sessionId: String,
    val stepNumber: Int,
    val originalPath: String,
    val modelPath: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
) {
    val scaleX: Float = originalWidth.toFloat() / modelWidth.toFloat()
    val scaleY: Float = originalHeight.toFloat() / modelHeight.toFloat()

    fun mapModelXToScreen(x: Double): Int {
        return ((x / 1000.0) * originalWidth).roundToInt().coerceIn(0, originalWidth - 1)
    }

    fun mapModelYToScreen(y: Double): Int {
        return ((y / 1000.0) * originalHeight).roundToInt().coerceIn(0, originalHeight - 1)
    }
}

class ScreenshotProcessor(
    private val context: Context,
    private val maxModelDimension: Int = 1600,
) {
    fun process(sessionId: String, stepNumber: Int, bitmap: Bitmap): ScreenshotFrame {
        val dir = File(context.filesDir, "ui_action_agent/screenshots/$sessionId")
        dir.mkdirs()

        val originalFile = File(dir, "step_${stepNumber}_original.png")
        savePng(bitmap, originalFile)

        val modelBitmap = resizeForModel(bitmap)
        val modelFile = if (modelBitmap === bitmap) {
            originalFile
        } else {
            File(dir, "step_${stepNumber}_model.png").also { savePng(modelBitmap, it) }
        }

        DbgLog.i(
            "Screenshot resize original=${bitmap.width}x${bitmap.height} " +
                "model=${modelBitmap.width}x${modelBitmap.height}",
        )

        return try {
            ScreenshotFrame(
                sessionId = sessionId,
                stepNumber = stepNumber,
                originalPath = originalFile.absolutePath,
                modelPath = modelFile.absolutePath,
                originalWidth = bitmap.width,
                originalHeight = bitmap.height,
                modelWidth = modelBitmap.width,
                modelHeight = modelBitmap.height,
            )
        } finally {
            if (modelBitmap !== bitmap) {
                modelBitmap.recycle()
            }
        }
    }

    private fun resizeForModel(bitmap: Bitmap): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxModelDimension) {
            return bitmap
        }

        val scale = maxModelDimension.toFloat() / longEdge.toFloat()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
