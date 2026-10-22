package com.example.volumebooster

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile to toggle the boost without opening the app. */
@TargetApi(Build.VERSION_CODES.N)
class BoostTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val on = !Prefs.enabled(this)
        if (on) {
            try {
                BoostService.start(this)
            } catch (e: Exception) {
                // Background start refused on this Android version: open the app instead.
                openApp()
                return
            }
        } else {
            BoostService.stop(this)
        }
        Prefs.setEnabled(this, on)
        refresh(on)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun refresh(on: Boolean = Prefs.enabled(this)) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (on) "${Prefs.boost(this)}%" else getString(R.string.off)
        }
        tile.updateTile()
    }
}
