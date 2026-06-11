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
        return (x * scaleX).roundToInt().coerceIn(0, originalWidth - 1)
    }

    fun mapModelYToScreen(y: Double): Int {
        return (y * scaleY).roundToInt().coerceIn(0, originalHeight - 1)
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

        val resized = resizeForModel(bitmap)
        val modelFile = File(dir, "step_${stepNumber}_model.png")
        savePng(resized, modelFile)
        val modelWidth = resized.width
        val modelHeight = resized.height

        if (resized !== bitmap) {
            resized.recycle()
        }

        return ScreenshotFrame(
            sessionId = sessionId,
            stepNumber = stepNumber,
            originalPath = originalFile.absolutePath,
            modelPath = modelFile.absolutePath,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            modelWidth = max(1, modelWidth),
            modelHeight = max(1, modelHeight),
        )
    }

    private fun resizeForModel(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxModelDimension) return bitmap
        val scale = maxModelDimension.toFloat() / longest.toFloat()
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
