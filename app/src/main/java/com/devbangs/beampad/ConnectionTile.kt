package com.devbangs.beampad

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile: BeamPad's connection, one swipe away. Shows the
 * state; a tap disconnects when connected and reconnects to the last device
 * otherwise. When the app is not running, or there is no device to go back
 * to, the tap opens the app instead, where pairing and permissions live.
 *
 * Read fresh each time the panel opens, and redrawn while it is open when
 * the connection changes ([refresh], called by HidService).
 */
class ConnectionTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        listening = this
        render()
    }

    override fun onStopListening() {
        if (listening === this) listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val service = HidService.running
        when {
            service == null -> open()
            service.isReady() -> service.disconnect()
            !service.reconnect() -> open()
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val service = HidService.running
        tile.state = if (service?.isReady() == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_beampad)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = service?.shortStatus() ?: getString(R.string.tile_open)
        }
        tile.updateTile()
    }

    /** The app as it was left, like its icon; unlocking first on the lock screen. */
    private fun open() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val go = {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
        if (isLocked) unlockAndRun { go() } else go()
    }

    companion object {
        /** The tile while the panel shows it, so a change can redraw it there and then. */
        private var listening: ConnectionTile? = null

        fun refresh(context: Context) {
            listening?.render()
            // Asks the system for a fresh look the next time it can.
            runCatching {
                TileService.requestListeningState(context, ComponentName(context, ConnectionTile::class.java))
            }
        }
    }
}
