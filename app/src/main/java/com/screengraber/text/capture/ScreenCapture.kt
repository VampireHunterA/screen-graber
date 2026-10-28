package com.screengraber.text.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Захватывает ровно один кадр экрана через уже полученный [MediaProjection].
 *
 * Ожидание кадра событийное (ImageReader + CountDownLatch), без «магических» пауз.
 * Все ресурсы освобождаются в finally.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection
) {

    fun capture(): Bitmap {
        val metrics = realDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = context.resources.displayMetrics.densityDpi

        val thread = HandlerThread("screen-capture").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val frameAvailable = CountDownLatch(1)

        reader.setOnImageAvailableListener({ frameAvailable.countDown() }, handler)

        var display: VirtualDisplay? = null
        var image: Image? = null

        try {
            display = projection.createVirtualDisplay(
                "screen-graber",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            if (!frameAvailable.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw TimeoutException("Screen frame was not produced in time")
            }

            image = reader.acquireLatestImage()
                ?: throw IllegalStateException("ImageReader returned no frame")

            return imageToBitmap(image, width, height)
        } finally {
            image?.close()
            display?.release()
            reader.close()
            thread.quitSafely()
        }
    }

    private fun realDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay
            .getRealMetrics(metrics)
        return metrics
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)

        if (paddedWidth == width) {
            return padded
        }

        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 2500L
    }
}
