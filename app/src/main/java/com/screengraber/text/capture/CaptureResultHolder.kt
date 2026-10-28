package com.screengraber.text.capture

import android.graphics.Bitmap

/**
 * Process-local передача кадра из [ProjectionService] в [CaptureActivity].
 *
 * Bitmap намеренно не передаётся через Binder (ResultReceiver/Intent): полноэкранный кадр
 * 1080×2340 может превысить лимит транзакции и привести к TransactionTooLargeException.
 * Сервис и Activity всегда живут в одном процессе.
 *
 * Сессия открывается в Activity до запуска сервиса и закрывается при любом завершении.
 * Публикация кадра после закрытия сессии отклоняется, поэтому «поздний» кадр не остаётся
 * в статическом поле.
 */
internal object CaptureResultHolder {

    private val lock = Any()
    private var active = false
    private var bitmap: Bitmap? = null

    fun begin() {
        synchronized(lock) {
            bitmap?.recycle()
            bitmap = null
            active = true
        }
    }

    /** Возвращает false, если сессия уже закрыта; тогда кадр нужно освободить вызывающему. */
    fun publish(frame: Bitmap): Boolean {
        synchronized(lock) {
            if (!active) return false
            bitmap?.recycle()
            bitmap = frame
            return true
        }
    }

    fun take(): Bitmap? {
        synchronized(lock) {
            val current = bitmap
            bitmap = null
            return current
        }
    }

    fun end() {
        synchronized(lock) {
            active = false
            bitmap?.recycle()
            bitmap = null
        }
    }
}
