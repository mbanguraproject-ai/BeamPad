package com.devbangs.beampad

import android.content.Context

/**
 * The one place that decides what Free and Pro get.
 *
 * Connecting, the keyboard with its navigation keys, the remote, media
 * keys, trackpad and mouse basics, devices, themes and diagnostics are
 * never locked: the app has to work properly on the free plan or nobody
 * stays long enough to upgrade. Pro adds power-user depth on top.
 */
object Features {

    /** Snippets a free user can keep. Existing ones above the cap are kept. */
    const val FREE_SNIPPET_LIMIT = 3

    /** Pro features, in paywall order. [key] is passed to the paywall to highlight one. */
    enum class Pro(val key: String) {
        FULL_KEYBOARD("full_keyboard"),
        COMMANDS("commands"),
        LAUNCH("launch"),
        AIR_MOUSE("air_mouse"),
        NO_ADS("no_ads"),
        PANELS("panels"),
        MACROS("macros"),
        PROFILES("profiles"),
        PRESENTATION("presentation"),
        GAMEPAD("gamepad"),
        TRACKPAD("trackpad"),
        LIVE_TYPING("live"),
        VOICE("voice"),
        CLIPBOARD("clipboard"),
        PRO_KEYS("keys"),
        SNIPPETS("snippets");

        companion object {
            fun of(key: String?): Pro? = entries.firstOrNull { it.key == key }
        }
    }

    private fun app(context: Context) = context.applicationContext as BeamPadApp

    /**
     * The gate for every Pro feature: paid Pro, or a day's [ProPass] from a
     * rewarded ad. What was paid for (ad removal, the Pro page's state) reads
     * [Entitlements] instead.
     */
    fun isPro(context: Context): Boolean =
        app(context).entitlements.isPro || ProPass.active(context)

    fun liveTyping(context: Context): Boolean =
        isPro(context) && app(context).prefs.liveTyping

    // Advanced trackpad: tuning, acceleration, gestures and precision are Pro.

    fun pointerSpeed(context: Context): Float =
        if (isPro(context)) app(context).prefs.pointerSpeed else Prefs.DEFAULT_POINTER_SPEED

    fun acceleration(context: Context): Float =
        if (isPro(context)) app(context).prefs.acceleration else Prefs.DEFAULT_ACCELERATION

    fun scrollSpeed(context: Context): Float =
        if (isPro(context)) app(context).prefs.scrollSpeed else Prefs.DEFAULT_SCROLL_SPEED

    fun reverseScroll(context: Context): Boolean =
        isPro(context) && app(context).prefs.reverseScroll

    fun pinchToZoom(context: Context): Boolean =
        isPro(context) && app(context).prefs.pinchToZoom

    /** Three-finger swipe action id for [direction], or null when not Pro or unset. */
    fun gesture(context: Context, direction: Prefs.SwipeDirection): String? =
        if (isPro(context)) app(context).prefs.gesture(direction) else null

    /**
     * Unlimited snippets come with paid Pro only. Snippets over the cap are
     * kept when Pro ends, so ones added during a day's pass would outlast it.
     */
    fun unlimitedSnippets(context: Context): Boolean = app(context).entitlements.isPro

    fun canAddSnippet(context: Context, current: Int): Boolean =
        unlimitedSnippets(context) || current < FREE_SNIPPET_LIMIT

    /** Per-device preferred mode, default panel and layout. */
    fun profiles(context: Context): Boolean = isPro(context)

    fun allowed(context: Context, mode: ControlMode): Boolean =
        mode.pro == null || isPro(context)
}
