package com.devbangs.beampad

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast

/**
 * Home screen widget: the connection's state, which opens the app, and
 * volume down, play/pause, volume up and mute for the connected device.
 *
 * The buttons go to the running connection ([HidService.running]); with no
 * device connected they say so and leave opening the app to the state line,
 * since a broadcast may not open a screen on its own. Redrawn when the
 * connection changes ([refresh]), so it never needs a timer.
 */
class ControlWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { manager.updateAppWidget(ids, views(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action !in KEYS) return
        val sent = HidService.running?.remoteKey(action) == true
        if (!sent) Toast.makeText(context, R.string.widget_not_connected, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val KEYS = setOf(
            HidService.ACTION_VOLUME_DOWN, HidService.ACTION_PLAY_PAUSE,
            HidService.ACTION_VOLUME_UP, HidService.ACTION_MUTE
        )

        /** Redraws every placed widget with the connection's current state. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ControlWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_controls).apply {
                setTextViewText(
                    R.id.widgetStatus,
                    HidService.running?.shortStatus() ?: context.getString(R.string.widget_open)
                )
                setOnClickPendingIntent(R.id.widgetHeader, openApp(context))
                setOnClickPendingIntent(R.id.widgetVolumeDown, key(context, HidService.ACTION_VOLUME_DOWN, 1))
                setOnClickPendingIntent(R.id.widgetPlayPause, key(context, HidService.ACTION_PLAY_PAUSE, 2))
                setOnClickPendingIntent(R.id.widgetVolumeUp, key(context, HidService.ACTION_VOLUME_UP, 3))
                setOnClickPendingIntent(R.id.widgetMute, key(context, HidService.ACTION_MUTE, 4))
            }

        private fun key(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, ControlWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE
            )

        /** The app as it was left, like its icon. */
        private fun openApp(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                PendingIntent.FLAG_IMMUTABLE
            )
    }
}
