package com.screengraber.text

import android.app.Activity
import android.os.Bundle

/**
 * Минимальный экран каркаса: название приложения и инструкция о будущей плитке.
 *
 * Захват экрана, выделение области и OCR на этом этапе не реализованы.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
