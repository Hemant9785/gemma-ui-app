package com.hemant.plannerv1.capture

import android.content.Context
import android.graphics.Bitmap
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
    private val maxModelDimension: Int = 1024,
) {
    fun process(sessionId: String, stepNumber: Int, bitmap: Bitmap): ScreenshotFrame {
        val dir = File(context.filesDir, "ui_action_agent/screenshots/$sessionId")
        dir.mkdirs()

        val originalFile = File(dir, "step_${stepNumber}_original.png")
        savePng(bitmap, originalFile)

        return ScreenshotFrame(
            sessionId = sessionId,
            stepNumber = stepNumber,
            originalPath = originalFile.absolutePath,
            modelPath = originalFile.absolutePath,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            modelWidth = bitmap.width,
            modelHeight = bitmap.height,
        )
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
