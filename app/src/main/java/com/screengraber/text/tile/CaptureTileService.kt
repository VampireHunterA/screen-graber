package com.screengraber.text.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.screengraber.text.MainActivity
import com.screengraber.text.R

/**
 * Плитка «Screen Graber» в шторке быстрых настроек.
 *
 * На этом этапе плитка только открывает [MainActivity].
 * Захват экрана, MediaProjection и OCR ещё не реализованы.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.tile_label)
            icon = Icon.createWithResource(this@CaptureTileService, R.drawable.ic_tile)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { openMainScreen() }
        } else {
            openMainScreen()
        }
    }

    private fun openMainScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Начиная с Android 14 вариант с Intent не поддерживается.
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.tile_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
