package com.devbangs.beampad

import android.content.Context

/**
 * User settings, in the same file the app has always used so existing keys
 * (layout, onboarding) carry over.
 *
 * Every setting the app has is declared here, so features share one source
 * of truth. Pro settings are stored as chosen but read through [Features],
 * which falls back to the defaults when Pro is not active: a lapsed
 * subscription reverts behaviour without losing the user's choices.
 */
class Prefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    private fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    private fun float(key: String, default: Float) = prefs.getFloat(key, default)
    private fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    private fun string(key: String): String? = prefs.getString(key, null)
    private fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        string(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    // ---- Connection -------------------------------------------------------

    /** Address of the last host that connected, for one-tap reconnect. */
    var lastHost: String?
        get() = string(KEY_LAST_HOST)
        set(value) = putString(KEY_LAST_HOST, value)

    var autoReconnect: Boolean
        get() = bool(KEY_AUTO_RECONNECT, true)
        set(value) = putBool(KEY_AUTO_RECONNECT, value)

    /** Keep trying, with backoff, after a connection drops unexpectedly. */
    var retryAfterDrop: Boolean
        get() = bool(KEY_RETRY_AFTER_DROP, true)
        set(value) = putBool(KEY_RETRY_AFTER_DROP, value)

    /** How long a connection attempt may take before it is reported as failed. */
    var connectTimeout: ConnectTimeout
        get() = enumOf(KEY_CONNECT_TIMEOUT, ConnectTimeout.NORMAL)
        set(value) = putString(KEY_CONNECT_TIMEOUT, value.name)

    var keepScreenOn: Boolean
        get() = bool(KEY_KEEP_SCREEN_ON, true)
        set(value) = putBool(KEY_KEEP_SCREEN_ON, value)

    /** A short confirmation tone on connect. Off by default. */
    var connectionSound: Boolean
        get() = bool(KEY_CONNECTION_SOUND, false)
        set(value) = putBool(KEY_CONNECTION_SOUND, value)

    // ---- Control ----------------------------------------------------------

    /** Last control mode shown, used when no device profile says otherwise. */
    var lastMode: ControlMode
        get() = enumOf(KEY_LAST_MODE, ControlMode.KEYBOARD)
        set(value) = putString(KEY_LAST_MODE, value.name)

    /** Custom panel shown last, when the Control screen was on a panel. */
    var lastPanelId: String?
        get() = string(KEY_LAST_PANEL)
        set(value) = putString(KEY_LAST_PANEL, value)

    // ---- Keyboard ---------------------------------------------------------

    var layout: HidReports.Layout
        get() = enumOf(KEY_LAYOUT, HidReports.Layout.US)
        set(value) = putString(KEY_LAYOUT, value.name)

    var liveTyping: Boolean
        get() = bool(KEY_LIVE_TYPING, false)
        set(value) = putBool(KEY_LIVE_TYPING, value)

    /** What the phone keyboard's action key does. */
    var enterBehavior: EnterBehavior
        get() = enumOf(KEY_ENTER_BEHAVIOR, EnterBehavior.SEND_AND_ENTER)
        set(value) = putString(KEY_ENTER_BEHAVIOR, value.name)

    var showFunctionKeys: Boolean
        get() = bool(KEY_SHOW_FKEYS, false)
        set(value) = putBool(KEY_SHOW_FKEYS, value)

    var showModifiers: Boolean
        get() = bool(KEY_SHOW_MODIFIERS, true)
        set(value) = putBool(KEY_SHOW_MODIFIERS, value)

    /** The keyboard tab shows the full PC keyboard instead of the phone's. */
    var fullKeyboard: Boolean
        get() = bool(KEY_FULL_KEYBOARD, false)
        set(value) = putBool(KEY_FULL_KEYBOARD, value)

    // ---- Trackpad and mouse -----------------------------------------------

    var pointerSpeed: Float
        get() = float(KEY_POINTER_SPEED, DEFAULT_POINTER_SPEED)
        set(value) = putFloat(KEY_POINTER_SPEED, value)

    /** 0 = linear, 1 = strong acceleration. */
    var acceleration: Float
        get() = float(KEY_ACCELERATION, DEFAULT_ACCELERATION)
        set(value) = putFloat(KEY_ACCELERATION, value)

    var scrollSpeed: Float
        get() = float(KEY_SCROLL_SPEED, DEFAULT_SCROLL_SPEED)
        set(value) = putFloat(KEY_SCROLL_SPEED, value)

    var reverseScroll: Boolean
        get() = bool(KEY_REVERSE_SCROLL, false)
        set(value) = putBool(KEY_REVERSE_SCROLL, value)

    var tapToClick: Boolean
        get() = bool(KEY_TAP_TO_CLICK, true)
        set(value) = putBool(KEY_TAP_TO_CLICK, value)

    var touchResponse: TouchResponse
        get() = enumOf(KEY_TOUCH_RESPONSE, TouchResponse.NORMAL)
        set(value) = putString(KEY_TOUCH_RESPONSE, value.name)

    var pinchToZoom: Boolean
        get() = bool(KEY_PINCH_ZOOM, true)
        set(value) = putBool(KEY_PINCH_ZOOM, value)

    /** Action ids from [Actions] for three-finger swipes, or null for none. */
    fun gesture(direction: SwipeDirection): String? =
        prefs.getString(KEY_GESTURE_PREFIX + direction.name, direction.defaultAction)

    fun setGesture(direction: SwipeDirection, actionId: String?) =
        putString(KEY_GESTURE_PREFIX + direction.name, actionId)

    // ---- Remote -----------------------------------------------------------

    var remoteLayout: RemoteLayout
        get() = enumOf(KEY_REMOTE_LAYOUT, RemoteLayout.STANDARD)
        set(value) = putString(KEY_REMOTE_LAYOUT, value.name)

    var buttonSize: ButtonSize
        get() = enumOf(KEY_BUTTON_SIZE, ButtonSize.REGULAR)
        set(value) = putString(KEY_BUTTON_SIZE, value.name)

    var haptics: Boolean
        get() = bool(KEY_HAPTICS, true)
        set(value) = putBool(KEY_HAPTICS, value)

    /** The phone's volume buttons drive the device: volume on Remote and Media, slides in Presentation. */
    var volumeButtons: Boolean
        get() = bool(KEY_VOLUME_BUTTONS, true)
        set(value) = putBool(KEY_VOLUME_BUTTONS, value)

    var hapticStrength: HapticStrength
        get() = enumOf(KEY_HAPTIC_STRENGTH, HapticStrength.LIGHT)
        set(value) = putString(KEY_HAPTIC_STRENGTH, value.name)

    // ---- Snippets ---------------------------------------------------------

    var snippetAutoLock: AutoLock
        get() = enumOf(KEY_AUTO_LOCK, AutoLock.EVERY_SEND)
        set(value) = putString(KEY_AUTO_LOCK, value.name)

    /** Ask before typing the phone's clipboard onto the device. */
    var confirmClipboard: Boolean
        get() = bool(KEY_CONFIRM_CLIPBOARD, false)
        set(value) = putBool(KEY_CONFIRM_CLIPBOARD, value)

    // ---- Appearance and accessibility -------------------------------------

    var appearance: Appearance
        get() = enumOf(KEY_APPEARANCE, Appearance.LIGHT)
        set(value) = putString(KEY_APPEARANCE, value.name)

    var largeControls: Boolean
        get() = bool(KEY_LARGE_CONTROLS, false)
        set(value) = putBool(KEY_LARGE_CONTROLS, value)

    var highContrast: Boolean
        get() = bool(KEY_HIGH_CONTRAST, false)
        set(value) = putBool(KEY_HIGH_CONTRAST, value)

    /** In addition to the system "remove animations" setting. */
    var reduceMotion: Boolean
        get() = bool(KEY_REDUCE_MOTION, false)
        set(value) = putBool(KEY_REDUCE_MOTION, value)

    // ---- Onboarding -------------------------------------------------------

    /** What the user said they control first, from onboarding. */
    var targetType: DeviceType
        get() = enumOf(KEY_TARGET_TYPE, DeviceType.TV)
        set(value) = putString(KEY_TARGET_TYPE, value.name)

    /** Set once the runtime permission dialog has been shown at least once. */
    var askedPermissions: Boolean
        get() = bool(KEY_ASKED_PERMISSIONS, false)
        set(value) = putBool(KEY_ASKED_PERMISSIONS, value)

    enum class ConnectTimeout(val millis: Long) { SHORT(10_000), NORMAL(15_000), LONG(30_000) }

    enum class EnterBehavior {
        /** Action key types the text, then presses Enter: search boxes submit. */
        SEND_AND_ENTER,

        /** Action key types the text only. */
        SEND_ONLY
    }

    enum class TouchResponse(val tapWindowScale: Float, val slopScale: Float) {
        RELAXED(1.6f, 1.5f), NORMAL(1f, 1f), QUICK(0.7f, 0.75f)
    }

    enum class SwipeDirection(val defaultAction: String?) {
        UP("app_switch"), DOWN("show_desktop"), LEFT("browser_back"), RIGHT("browser_forward")
    }

    enum class RemoteLayout { STANDARD, MINIMAL, FULL }

    enum class ButtonSize(val scale: Float) { COMPACT(0.85f), REGULAR(1f), LARGE(1.2f) }

    enum class HapticStrength { LIGHT, MEDIUM, STRONG }

    enum class AutoLock(val graceMillis: Long) {
        EVERY_SEND(0), ONE_MINUTE(60_000), FIVE_MINUTES(300_000)
    }

    companion object {
        const val FILE = "beampad"
        const val DEFAULT_POINTER_SPEED = 1.6f
        const val DEFAULT_SCROLL_SPEED = 1f
        const val DEFAULT_ACCELERATION = 0f

        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_RETRY_AFTER_DROP = "retry_after_drop"
        private const val KEY_CONNECT_TIMEOUT = "connect_timeout"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_CONNECTION_SOUND = "connection_sound"
        private const val KEY_LAST_MODE = "last_mode"
        private const val KEY_LAST_PANEL = "last_panel"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_LIVE_TYPING = "live_typing"
        private const val KEY_ENTER_BEHAVIOR = "enter_behavior"
        private const val KEY_SHOW_FKEYS = "show_fkeys"
        private const val KEY_SHOW_MODIFIERS = "show_modifiers"
        private const val KEY_FULL_KEYBOARD = "full_keyboard"
        private const val KEY_POINTER_SPEED = "pointer_speed"
        private const val KEY_ACCELERATION = "acceleration"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_REVERSE_SCROLL = "reverse_scroll"
        private const val KEY_TAP_TO_CLICK = "tap_to_click"
        private const val KEY_TOUCH_RESPONSE = "touch_response"
        private const val KEY_PINCH_ZOOM = "pinch_zoom"
        private const val KEY_GESTURE_PREFIX = "gesture_"
        private const val KEY_REMOTE_LAYOUT = "remote_layout"
        private const val KEY_BUTTON_SIZE = "button_size"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
        private const val KEY_VOLUME_BUTTONS = "volume_buttons"
        private const val KEY_AUTO_LOCK = "snippet_auto_lock"
        private const val KEY_CONFIRM_CLIPBOARD = "confirm_clipboard"
        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_LARGE_CONTROLS = "large_controls"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_TARGET_TYPE = "target_type"
        private const val KEY_ASKED_PERMISSIONS = "asked_permissions"
    }
}
