package com.screengraber.text.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import com.screengraber.text.MainActivity
import com.screengraber.text.R
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Короткоживущий foreground service для одного сеанса MediaProjection.
 *
 * Обязан работать до вызова [MediaProjectionManager.getMediaProjection] при targetSdk >= 29.
 * Захватывает один кадр и сразу останавливается; разрешение не кэшируется.
 */
class ProjectionService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val projectionReleased = AtomicBoolean(false)

    @Volatile
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!busy.compareAndSet(false, true)) {
            // Защита от повторного запуска во время активного сеанса.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)

        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_DATA)

        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)

        if (resultCode != Activity.RESULT_OK || resultData == null || receiver == null) {
            finishSession(startId)
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        executor.execute {
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val mediaProjection = manager.getMediaProjection(resultCode, resultData)
                projection = mediaProjection

                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        releaseProjection()
                    }
                }, mainHandler)

                val bitmap = ScreenCapture(this, mediaProjection).capture()
                if (CaptureResultHolder.publish(bitmap)) {
                    receiver.send(RESULT_OK, null)
                } else {
                    // Activity уже закрылась: кадр больше некому показывать.
                    bitmap.recycle()
                    receiver.send(RESULT_ERROR, null)
                }
            } catch (e: TimeoutException) {
                receiver.send(RESULT_TIMEOUT, null)
            } catch (e: SecurityException) {
                receiver.send(RESULT_SECURITY, null)
            } catch (e: Throwable) {
                receiver.send(RESULT_ERROR, null)
            } finally {
                releaseProjection()
                finishSession(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun finishSession(startId: Int) {
        mainHandler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            busy.set(false)
        }
    }

    /** Идемпотентно останавливает projection; повторные вызовы безопасны. */
    private fun releaseProjection() {
        if (!projectionReleased.compareAndSet(false, true)) return
        val current = projection
        projection = null
        if (current != null) {
            runCatching { current.stop() }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        releaseProjection()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "com.screengraber.text.extra.RESULT_CODE"
        const val EXTRA_DATA = "com.screengraber.text.extra.DATA"
        const val EXTRA_RECEIVER = "com.screengraber.text.extra.RECEIVER"

        const val RESULT_OK = 1
        const val RESULT_TIMEOUT = 2
        const val RESULT_SECURITY = 3
        const val RESULT_ERROR = 4

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 42
    }
}
