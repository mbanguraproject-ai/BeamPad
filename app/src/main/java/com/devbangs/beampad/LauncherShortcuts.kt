package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * Long-press the app icon: Keyboard, Trackpad, Remote and Media, each
 * straight to that surface. Published from code rather than a static
 * shortcuts.xml, which has to name one package and so would break the
 * debug build's. A launcher that refuses them (rate limits, restricted
 * profiles) just shows none.
 */
object LauncherShortcuts {

    private val MODES = listOf(
        Triple(ControlMode.KEYBOARD, R.string.mode_keyboard, R.drawable.ic_shortcut_keyboard),
        Triple(ControlMode.TRACKPAD, R.string.mode_trackpad, R.drawable.ic_shortcut_trackpad),
        Triple(ControlMode.REMOTE, R.string.mode_remote, R.drawable.ic_shortcut_remote),
        Triple(ControlMode.MEDIA, R.string.mode_media, R.drawable.ic_shortcut_media)
    )

    fun publish(context: Context) {
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val shortcuts = MODES.mapIndexed { rank, (mode, label, icon) ->
                ShortcutInfo.Builder(context, "mode_${mode.name.lowercase()}")
                    .setShortLabel(context.getString(label))
                    .setLongLabel(context.getString(R.string.shortcut_open, context.getString(label)))
                    .setIcon(Icon.createWithResource(context, icon))
                    .setRank(rank)
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .putExtra(MainActivity.EXTRA_MODE, mode.name)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    .build()
            }
            manager.setDynamicShortcuts(shortcuts.take(manager.maxShortcutCountPerActivity.coerceAtLeast(1)))
        }
    }
}
