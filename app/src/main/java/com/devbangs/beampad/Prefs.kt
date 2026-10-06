package com.devbangs.beampad

import android.content.Context

/**
 * User settings, in the same file the app has always used so existing keys
 * (layout, onboarding) carry over.
 *
 * Pro settings are stored as chosen but read through [Features], which
 * falls back to the defaults when Pro is not active. A lapsed subscription
 * therefore reverts behaviour without losing the user's choices.
 */
class Prefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Address of the last host that connected, for one-tap reconnect. */
    var lastHost: String?
        get() = prefs.getString(KEY_LAST_HOST, null)
        set(value) = prefs.edit().putString(KEY_LAST_HOST, value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var liveTyping: Boolean
        get() = prefs.getBoolean(KEY_LIVE_TYPING, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_TYPING, value).apply()

    var pointerSpeed: Float
        get() = prefs.getFloat(KEY_POINTER_SPEED, DEFAULT_POINTER_SPEED)
        set(value) = prefs.edit().putFloat(KEY_POINTER_SPEED, value).apply()

    var scrollSpeed: Float
        get() = prefs.getFloat(KEY_SCROLL_SPEED, DEFAULT_SCROLL_SPEED)
        set(value) = prefs.edit().putFloat(KEY_SCROLL_SPEED, value).apply()

    var reverseScroll: Boolean
        get() = prefs.getBoolean(KEY_REVERSE_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_REVERSE_SCROLL, value).apply()

    var layout: HidReports.Layout
        get() = runCatching {
            HidReports.Layout.valueOf(
                prefs.getString(KEY_LAYOUT, null) ?: HidReports.Layout.US.name
            )
        }.getOrDefault(HidReports.Layout.US)
        set(value) = prefs.edit().putString(KEY_LAYOUT, value.name).apply()

    /** Set once the runtime permission dialog has been shown at least once. */
    var askedPermissions: Boolean
        get() = prefs.getBoolean(KEY_ASKED_PERMISSIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_PERMISSIONS, value).apply()

    companion object {
        const val FILE = "beampad"
        const val DEFAULT_POINTER_SPEED = 1.6f
        const val DEFAULT_SCROLL_SPEED = 1f

        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_LIVE_TYPING = "live_typing"
        private const val KEY_POINTER_SPEED = "pointer_speed"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_REVERSE_SCROLL = "reverse_scroll"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_ASKED_PERMISSIONS = "asked_permissions"
    }
}
