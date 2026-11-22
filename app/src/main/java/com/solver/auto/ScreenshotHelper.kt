package com.solver.auto

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class ScreenshotHelper(private val service: AccessibilityService) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private var lastAttempt = 0L

    suspend fun captureOCR(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val text = ocrOnce(1024)
        return if (text.trim().length < 25) ocrOnce(0).ifBlank { text } else text
    }

    private suspend fun ocrOnce(maxW: Int): String {
        val gap = System.currentTimeMillis() - lastAttempt
        if (gap < 1100) delay(1100 - gap)
        lastAttempt = System.currentTimeMillis()

        val bmp = captureArgb() ?: return ""
        try {
            val use = if (maxW in 1 until bmp.width) downscale(bmp, maxW) else bmp
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(use, 0))
                    .addOnSuccessListener { if (!cont.isCompleted) cont.resume(it.text) {} }
                    .addOnFailureListener { if (!cont.isCompleted) cont.resume("") {} }
            }
            if (use !== bmp && !use.isRecycled) use.recycle()
            return text
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private suspend fun captureArgb(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(res: AccessibilityService.ScreenshotResult) {
                        val hb = res.hardwareBuffer
                        var out: Bitmap? = null
                        try {
                            Bitmap.wrapHardwareBuffer(hb, res.colorSpace)?.let { hw ->
                                out = Bitmap.createBitmap(hw.width, hw.height, Bitmap.Config.ARGB_8888)
                                Canvas(out!!).drawBitmap(hw, 0f, 0f, null)
                                hw.recycle()
                            }
                        } finally {
                            hb.close()
                            if (!cont.isCompleted) cont.resume(out) {}
                        }
                    }
                    override fun onFailure(code: Int) {
                        if (!cont.isCompleted) cont.resume(null) {}
                    }
                })
        } catch (e: Exception) {
            if (!cont.isCompleted) cont.resume(null) {}
        }
    }

    private fun downscale(src: Bitmap, maxW: Int): Bitmap {
        val ratio = maxW.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, maxW, (src.height * ratio).toInt(), true)
    }

    fun release() { recognizer.close(); executor.shutdown() }
}
