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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import com.screengraber.text.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.max

/**
 * Захватывает ровно один кадр экрана через уже полученный [MediaProjection].
 *
 * Ожидание кадра событийное (ImageReader + CountDownLatch), без «магических» пауз.
 * Все ресурсы освобождаются в finally.
 *
 * Диагностика размытия (временно): после выбора кадра монитор считает резкость
 * выбранного кадра и наблюдает последующие кадры в течение [MONITOR_MS]. Выбор кадра
 * для Bitmap не меняется — по-прежнему первый доступный кадр.
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
        val startedAt = SystemClock.elapsedRealtime()

        Timing.mark("capture_start", "requested=${width}x$height densityDpi=$densityDpi")

        val thread = HandlerThread("screen-capture").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val frameAvailable = CountDownLatch(1)
        // Диагностика — только в debug. В release монитор не создаётся и показ не задерживается.
        val monitor = if (BuildConfig.DEBUG) FrameMonitor() else null
        val monitorUntil = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() + MONITOR_MS else 0L

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
            Timing.mark("virtual_display_created", "size=${width}x$height")

            if (!frameAvailable.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw TimeoutException("Screen frame was not produced in time")
            }

            image = reader.acquireLatestImage()
                ?: throw IllegalStateException("ImageReader returned no frame")

            val timestampNs = image.timestamp
            val ageMs = (System.nanoTime() - timestampNs) / 1_000_000
            Timing.mark(
                "first_image",
                "image=${image.width}x${image.height} imageTimestampNs=$timestampNs " +
                    "ageMs=$ageMs waitMs=${SystemClock.elapsedRealtime() - startedAt}"
            )

            val bitmap = imageToBitmap(image, width, height)
            // Кадр скопирован в Bitmap; закрываем Image сразу, чтобы освободить буфер
            // ImageReader для последующих кадров, которые снимает монитор.
            image.close()
            image = null

            Timing.mark(
                "bitmap_ready",
                "bitmap=${bitmap.width}x${bitmap.height} " +
                    "totalMs=${SystemClock.elapsedRealtime() - startedAt}"
            )

            monitor?.recordSelected(bitmap, timestampNs, startedAt)
            savePng(bitmap, "first")
            monitor?.drain(reader, monitorUntil, startedAt)
            monitor?.lastFrame()?.let { savePng(it, "last") }

            return bitmap
        } finally {
            image?.close()
            display?.release()
            reader.close()
            thread.quitSafely()
            monitor?.release()
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

    /** Сохраняет кадр в PNG только в debug-сборке; в release — no-op. */
    private fun savePng(bitmap: Bitmap, label: String) {
        if (!BuildConfig.DEBUG) return
        val dir = context.getExternalFilesDir(null)
        if (dir == null) {
            Log.d(Timing.TAG, "frame_save_failed label=$label reason=no_external_dir")
            return
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.d(Timing.TAG, "frame_save_failed label=$label reason=mkdirs")
            return
        }
        // Имя содержит метку времени, поэтому старые файлы никогда не перезаписываются.
        val file = File(dir, "frame-$label-${SAVE_STAMP.format(Date())}.png")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(Timing.TAG, "frame_saved label=$label path=${file.absolutePath}")
        } catch (e: Throwable) {
            Log.d(Timing.TAG, "frame_save_failed label=$label err=${e.javaClass.simpleName}")
        }
    }

    /**
     * Диагностика размытия. Не участвует в выборе кадра для Bitmap: работает уже после
     * того, как выбранный кадр скопирован и освобождён, поэтому не конкурирует с
     * `acquireLatestImage()` основного пути.
     */
    private inner class FrameMonitor {

        private var index = 0
        private var last: Bitmap? = null

        fun recordSelected(bitmap: Bitmap, timestampNs: Long, startedAt: Long) {
            index++
            logFrame(index, timestampNs, bitmap, startedAt)
        }

        /** Снимает кадры до истечения окна наблюдения; каждый освобождает буфер сразу. */
        fun drain(reader: ImageReader, until: Long, startedAt: Long) {
            if (!BuildConfig.DEBUG) return
            while (SystemClock.elapsedRealtime() < until) {
                val frame = reader.acquireLatestImage()
                if (frame == null) {
                    Thread.sleep(5)
                    continue
                }
                val bitmap = imageToBitmap(frame, frame.width, frame.height)
                val timestampNs = frame.timestamp
                frame.close()
                index++
                logFrame(index, timestampNs, bitmap, startedAt)
                last?.recycle()
                last = bitmap
            }
        }

        fun lastFrame(): Bitmap? = last

        fun release() {
            last?.recycle()
            last = null
        }

        private fun logFrame(n: Int, timestampNs: Long, bitmap: Bitmap, startedAt: Long) {
            Timing.mark(
                "monitor_frame",
                "n=$n sinceMs=${SystemClock.elapsedRealtime() - startedAt} " +
                    "imageTimestampNs=$timestampNs size=${bitmap.width}x${bitmap.height} " +
                    "sharpness=%.2f".format(laplacianVariance(bitmap))
            )
        }
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 2500L

        // --- Диагностика размытия (временно) -----------------------------------
        const val MONITOR_MS = 1500L

        /** Ширина уменьшенной копии для оценки резкости (ускоряет расчёт дисперсии). */
        const val SHARPNESS_SAMPLE_WIDTH = 270

        private val SAVE_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

        /** Резкость = дисперсия лапласиана по яркости на уменьшенной копии. Больше = резче. */
        fun laplacianVariance(bitmap: Bitmap): Double {
            val scaled = downscaleForSharpness(bitmap)
            val w = scaled.width
            val h = scaled.height
            if (w < 3 || h < 3) return -1.0
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            if (scaled !== bitmap) scaled.recycle()
            var mean = 0.0
            var meanSq = 0.0
            var count = 0
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val lap = 4.0 * luminance(pixels[row + x]) -
                        luminance(pixels[row + x - 1]) -
                        luminance(pixels[row + x + 1]) -
                        luminance(pixels[(y - 1) * w + x]) -
                        luminance(pixels[(y + 1) * w + x])
                    mean += lap
                    meanSq += lap * lap
                    count++
                }
            }
            if (count == 0) return -1.0
            val m = mean / count
            return meanSq / count - m * m
        }

        private fun luminance(pixel: Int): Double {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return 0.299 * r + 0.587 * g + 0.114 * b
        }

        private fun downscaleForSharpness(source: Bitmap): Bitmap {
            val longest = max(source.width, source.height)
            if (longest <= SHARPNESS_SAMPLE_WIDTH) return source
            val scale = SHARPNESS_SAMPLE_WIDTH.toFloat() / longest
            val w = (source.width * scale).toInt().coerceAtLeast(1)
            val h = (source.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(source, w, h, true)
        }
        // -----------------------------------------------------------------------
    }
}
