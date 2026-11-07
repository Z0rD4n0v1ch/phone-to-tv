package com.example.phonetotv

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Плитка «Экран на ТВ» в шторке уведомлений: одно нажатие — начать трансляцию на последнюю приставку
 * (система один раз спросит разрешение на захват экрана), ещё одно — остановить.
 */
class CastTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = CastService.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Экран на ТВ"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (running) "Идёт трансляция" else "Начать"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (CastService.isRunning) {
            CastService.stop(this)
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        val intent = Intent(this, SenderActivity::class.java)
            .putExtra(SenderActivity.EXTRA_AUTO_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = Runnable {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }
}
