package com.screengraber.text.capture

import android.os.SystemClock
import android.util.Log

/**
 * Временные диагностические метки для измерения пути кадра.
 *
 * Только локальный logcat, без сети и телеметрии. Будет удалён после проверки
 * на устройстве.
 */
internal object Timing {

    const val TAG = "ScreenGraberTiming"

    private var start = 0L

    fun start() {
        start = SystemClock.elapsedRealtime()
        Log.d(TAG, "t0 activity_create at=$start")
    }

    fun mark(label: String, details: String = "") {
        val now = SystemClock.elapsedRealtime()
        val delta = if (start == 0L) -1L else now - start
        Log.d(TAG, "$label dt=${delta}ms at=$now $details")
    }
}
