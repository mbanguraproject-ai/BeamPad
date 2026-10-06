package com.devbangs.beampad

import android.content.Context

/**
 * The one place that decides what Free and Pro get.
 *
 * Connecting, the keyboard, trackpad and remote are never locked: the app
 * has to work properly on the free plan or nobody stays long enough to
 * upgrade. Pro adds convenience on top.
 */
object Features {

    /** Snippets a free user can keep. Existing ones above the cap are kept. */
    const val FREE_SNIPPET_LIMIT = 3

    /** Pro features, in paywall order. [key] is passed to the paywall to highlight one. */
    enum class Pro(val key: String) {
        NO_ADS("no_ads"),
        LIVE_TYPING("live"),
        VOICE("voice"),
        CLIPBOARD("clipboard"),
        PRO_KEYS("keys"),
        SNIPPETS("snippets"),
        TRACKPAD("trackpad");

        companion object {
            fun of(key: String?): Pro? = entries.firstOrNull { it.key == key }
        }
    }

    private fun app(context: Context) = context.applicationContext as BeamPadApp

    fun isPro(context: Context): Boolean = app(context).entitlements.isPro

    fun liveTyping(context: Context): Boolean =
        isPro(context) && app(context).prefs.liveTyping

    fun pointerSpeed(context: Context): Float =
        if (isPro(context)) app(context).prefs.pointerSpeed else Prefs.DEFAULT_POINTER_SPEED

    fun scrollSpeed(context: Context): Float =
        if (isPro(context)) app(context).prefs.scrollSpeed else Prefs.DEFAULT_SCROLL_SPEED

    fun reverseScroll(context: Context): Boolean =
        isPro(context) && app(context).prefs.reverseScroll

    fun canAddSnippet(context: Context, current: Int): Boolean =
        isPro(context) || current < FREE_SNIPPET_LIMIT
}
