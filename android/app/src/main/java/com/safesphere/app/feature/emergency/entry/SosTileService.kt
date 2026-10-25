package com.safesphere.app.feature.emergency.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Phase 13F (F11): the SOS tile in Quick Settings. A tap opens [SosEntryActivity] (the countdown starts and can be
 * cancelled for 5 s). On a locked phone the system first asks to unlock: the app's screens never show over the lock
 * screen. Only the system can bind this service (BIND_QUICK_SETTINGS_TILE, manifest).
 */
class SosTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { openSos() } else openSos()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSos() {
        val intent = Intent(this, SosEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
