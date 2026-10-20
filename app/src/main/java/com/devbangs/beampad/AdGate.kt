package com.devbangs.beampad

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.android.ump.UserMessagingPlatform

/**
 * What the full-screen ads (app open, interstitial, rewarded) share, so
 * they never stack or step on each other:
 *
 * - which one is on screen now ([begin] / [end]), so an app open ad never
 *   lands on a return from another ad, and an interstitial never follows
 *   one straight away ([sinceLastEnd]);
 * - the AdMob screen itself, watched here as a fallback: if it closes and
 *   the ad never says so, [onAdScreenGone] still lets the app carry on, so
 *   a lost callback cannot leave a splash, a cover or a gate stuck;
 * - the eligibility every format checks first ([eligible]);
 * - in debug builds only, a short log of what each format did and why,
 *   shown in Diagnostics, so an ad that did not appear is never a mystery
 *   while developing; the store build keeps and shows none of it.
 */
object AdGate : Application.ActivityLifecycleCallbacks {

    private const val TAG = "BeamPadAds"

    /** New users get a few clean launches first, as AdMob recommends. */
    const val MIN_LAUNCHES = 3

    const val FILE = "beampad_ads"
    private const val KEY_LAUNCHES = "launches"

    /** A showing flag older than this is stale (a callback lost for good). */
    private const val MAX_SHOW_MS = 10 * 60 * 1000L

    private const val LOG_SIZE = 30

    /** Set at startup by [init]; the release build keeps no ad log. */
    private var debug = false

    fun init(context: Context) {
        debug = debugBuild(context)
    }

    private var owner: String? = null
    private var since = 0L
    private var lastEnd = 0L
    private var adScreens = 0

    /** Told when the AdMob screen has gone, whatever the ad reported. */
    private val goneListeners = mutableListOf<() -> Unit>()

    /** Whether a full-screen ad is on screen (or being put there) now. */
    val showing: Boolean
        get() = owner != null && SystemClock.elapsedRealtime() - since < MAX_SHOW_MS

    fun begin(who: String) {
        owner = who
        since = SystemClock.elapsedRealtime()
    }

    fun end(who: String) {
        if (owner != who) return
        owner = null
        lastEnd = SystemClock.elapsedRealtime()
    }

    /** Milliseconds since the last full-screen ad closed; large when none has. */
    fun sinceLastEnd(): Long =
        if (lastEnd == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastEnd

    fun onAdScreenGone(listener: () -> Unit) {
        goneListeners += listener
    }

    // ---- Eligibility ----------------------------------------------------------

    /** Counts a real launch (not a restore). Called once per launch by the app open ad. */
    fun countLaunch(context: Context): Int {
        val store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val launches = store.getInt(KEY_LAUNCHES, 0) + 1
        store.edit().putInt(KEY_LAUNCHES, launches).apply()
        return launches
    }

    fun launches(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_LAUNCHES, 0)

    /**
     * Why no full-screen ad may show now, or null when one may. Ads removed
     * is checked first, so paying users never touch the SDK. [graceLaunches]
     * is how many first launches stay ad-free: [MIN_LAUNCHES] for the app
     * open ad, as AdMob recommends; fewer for formats that follow the user's
     * own taps.
     */
    fun blocked(context: Context, graceLaunches: Int = MIN_LAUNCHES): String? = when {
        adsRemoved(context) -> "ads removed"
        OnboardingActivity.shouldShow(context) -> "onboarding"
        launches(context) <= graceLaunches -> "launch ${launches(context)} of the first $graceLaunches"
        !consentAllows(context) -> "no consent yet"
        else -> null
    }

    fun eligible(context: Context, graceLaunches: Int = MIN_LAUNCHES): Boolean =
        blocked(context, graceLaunches) == null

    fun adsRemoved(context: Context): Boolean =
        (context.applicationContext as BeamPadApp).entitlements.adsRemoved

    /** The stored consent from earlier sessions, available without a network round trip. */
    fun consentAllows(context: Context): Boolean =
        runCatching { UserMessagingPlatform.getConsentInformation(context).canRequestAds() }
            .getOrDefault(false)

    /** Debug builds only show the ad log (Diagnostics); the store build never does. */
    fun debugBuild(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun isAdScreen(activity: Activity): Boolean =
        activity.javaClass.name == "com.google.android.gms.ads.AdActivity"

    // ---- Log ------------------------------------------------------------------

    data class Event(val at: Long, val format: String, val what: String)

    private val events = ArrayDeque<Event>()

    fun log(format: String, what: String) {
        // Debug builds only: the store build records nothing about its ads.
        if (!debug) return
        Log.i(TAG, "$format: $what")
        synchronized(events) {
            events.addLast(Event(System.currentTimeMillis(), format, what))
            while (events.size > LOG_SIZE) events.removeFirst()
        }
    }

    /** Newest first. */
    fun recent(): List<Event> = synchronized(events) { events.reversed() }

    // ---- The AdMob screen -----------------------------------------------------

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (isAdScreen(activity)) adScreens++
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (!isAdScreen(activity)) return
        adScreens = (adScreens - 1).coerceAtLeast(0)
        if (adScreens == 0) goneListeners.toList().forEach { it() }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
