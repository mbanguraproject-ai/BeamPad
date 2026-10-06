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
        NO_ADS("no_ads"),
        PANELS("panels"),
        MACROS("macros"),
        PROFILES("profiles"),
        PRESENTATION("presentation"),
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

    fun isPro(context: Context): Boolean = app(context).entitlements.isPro

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

    fun canAddSnippet(context: Context, current: Int): Boolean =
        isPro(context) || current < FREE_SNIPPET_LIMIT

    /** Per-device preferred mode, default panel and layout. */
    fun profiles(context: Context): Boolean = isPro(context)

    fun allowed(context: Context, mode: ControlMode): Boolean =
        mode.pro == null || isPro(context)
}
