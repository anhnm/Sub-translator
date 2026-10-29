package com.subtranslator

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that toggles live subtitles. */
class SubtitleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (SubtitleService.isRunning) {
            SubtitleService.stop(this)
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.updateTile()
            return
        }
        val intent = StartCaptureActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (SubtitleService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val prefs = Prefs(this)
        tile.subtitle = "${prefs.language.label} → ${Languages.get(prefs.targetLang).label}"
        tile.updateTile()
    }
}
