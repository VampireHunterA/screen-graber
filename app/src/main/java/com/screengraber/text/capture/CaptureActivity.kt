package com.screengraber.text.capture

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.screengraber.text.R
import com.screengraber.text.ui.SelectionView

/**
 * Один сеанс захвата экрана с выбором прямоугольной области.
 *
 * Окно прозрачно до получения кадра, поэтому сама Activity не попадает в снимок.
 * После кадра показывается [SelectionView]: затемнение вне выделения, надпись
 * «Выделите область» и кнопка «Скопировать» после выделения.
 *
 * OCR на этом этапе не реализован: кнопка показывает только заглушку.
 */
class CaptureActivity : Activity() {

    private lateinit var root: View
    private lateinit var selectionView: SelectionView
    private lateinit var errorPanel: View
    private lateinit var status: TextView
    private lateinit var closeButton: Button
    private lateinit var copyButton: Button

    private var frame: Bitmap? = null
    private var resultShown = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        if (!resultShown && !isFinishing && !isDestroyed) {
            resultShown = true
            stopService(Intent(this, ProjectionService::class.java))
            showError(getString(R.string.capture_error_timeout))
        }
    }

    private val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            mainHandler.removeCallbacks(watchdog)
            if (isFinishing || isDestroyed || resultShown) return
            resultShown = true
            when (resultCode) {
                ProjectionService.RESULT_OK -> {
                    val bitmap = CaptureResultHolder.take()
                    if (bitmap != null) {
                        showFrame(bitmap)
                    } else {
                        showError(getString(R.string.capture_error_generic))
                    }
                }
                ProjectionService.RESULT_TIMEOUT ->
                    showError(getString(R.string.capture_error_timeout))
                ProjectionService.RESULT_SECURITY ->
                    showError(getString(R.string.capture_error_security))
                else ->
                    showError(getString(R.string.capture_error_generic))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ориентация фиксируется на сеанс, чтобы координаты кадра не «поехали».
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        setContentView(R.layout.activity_capture)
        root = findViewById(R.id.capture_root)
        selectionView = findViewById(R.id.capture_selection)
        errorPanel = findViewById(R.id.capture_error_panel)
        status = findViewById(R.id.capture_status)
        closeButton = findViewById(R.id.capture_close)
        copyButton = findViewById(R.id.capture_copy)

        root.visibility = View.INVISIBLE
        closeButton.setOnClickListener { finishSession() }
        copyButton.setOnClickListener {
            // OCR появится на следующем этапе; фиктивного копирования нет.
            Toast.makeText(this, R.string.selection_copy_stub, Toast.LENGTH_SHORT).show()
        }
        selectionView.onSelectionChanged = { hasSelection ->
            copyButton.visibility = if (hasSelection) View.VISIBLE else View.GONE
        }

        CaptureResultHolder.begin()

        if (savedInstanceState == null) {
            requestScreenCapture()
        } else {
            finishSession()
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            finishSession()
            return
        }

        val intent = Intent(this, ProjectionService::class.java).apply {
            putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProjectionService.EXTRA_DATA, data)
            putExtra(ProjectionService.EXTRA_RECEIVER, receiver)
        }
        try {
            startForegroundService(intent)
            mainHandler.postDelayed(watchdog, WATCHDOG_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            CaptureResultHolder.end()
            showError(getString(R.string.capture_error_generic))
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        finishSession()
    }

    private fun showFrame(bitmap: Bitmap) {
        frame?.recycle()
        frame = bitmap
        selectionView.setBitmap(bitmap)
        copyButton.visibility = View.GONE
        errorPanel.visibility = View.GONE
        root.setBackgroundColor(Color.BLACK)
        root.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        selectionView.setBitmap(null)
        selectionView.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
        copyButton.visibility = View.GONE
        status.text = message
        root.setBackgroundColor(Color.BLACK)
        root.visibility = View.VISIBLE
    }

    private fun finishSession() {
        mainHandler.removeCallbacks(watchdog)
        selectionView.setBitmap(null)
        CaptureResultHolder.end()
        stopService(Intent(this, ProjectionService::class.java))
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(watchdog)
        selectionView.setBitmap(null)
        CaptureResultHolder.end()
        frame?.recycle()
        frame = null
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CAPTURE = 1001
        const val WATCHDOG_TIMEOUT_MS = 10_000L
    }
}
