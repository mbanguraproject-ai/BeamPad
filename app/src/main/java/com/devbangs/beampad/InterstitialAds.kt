package com.devbangs.beampad

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Interstitial at page transitions, the natural breaks AdMob asks for:
 * switching tabs, switching control surfaces with the mode bar or Home's
 * tiles, and opening a page such as Settings. Every [TRANSITIONS_PER_AD]th
 * transition shows one as the new page appears, and the user lands on the
 * page they chose when it closes.
 *
 * Never two full-screen ads back to back: at least [MIN_GAP_MS] after any
 * of them (the app open ad included) has closed. A transition that is due
 * but cannot show (too soon, nothing loaded yet) keeps its place, so the
 * next transition shows it. Never on the Pro page, never on connect (a
 * device profile switching the surface is not the user's transition), and
 * never for Pro or remove-ads users. Only the very first launch (setup
 * and first pairing) is ad-free, not the app open ad's first
 * [AdGate.MIN_LAUNCHES]: these follow the user's own taps. Every page
 * change is logged with its count, so Diagnostics shows it working.
 *
 * One ad is kept loaded ahead; a failed load waits a little longer each
 * time before the next try, so a lack of fill does not hammer AdMob.
 */
object InterstitialAds : Application.ActivityLifecycleCallbacks {

    private const val AD_UNIT = "ca-app-pub-9121922395304175/1693922207"
    private const val FORMAT = "Interstitial"

    const val TRANSITIONS_PER_AD = 3

    /** Since any full-screen ad closed. */
    private const val MIN_GAP_MS = 15_000L

    /** First launches without interstitials: the one with setup and first pairing. */
    const val GRACE_LAUNCHES = 1

    /** AdMob expires interstitials after an hour. */
    private const val MAX_AD_AGE_MS = 60 * 60 * 1000L

    /** Covers an ad that is told to show but never reports back. */
    private const val SHOW_TIMEOUT_MS = 3_000L

    private val RETRY_DELAYS_MS = longArrayOf(10_000, 30_000, 60_000, 120_000, 300_000)

    private const val KEY_TRANSITIONS = "interstitial_transitions"

    private var ad: InterstitialAd? = null
    private var loadedAt = 0L
    private var loading = false
    private var failures = 0
    private var retryAt = 0L

    private var presenting = false
    private var session = 0

    /** A due ad waiting for this screen to be in front. */
    private var pending: Activity? = null

    /** The last reason logged for not counting, so the log is not flooded with it. */
    private var lastBlocked: String? = null
    private var resumedActivity: Activity? = null

    private val main = Handler(Looper.getMainLooper())
    private val showTimeout = Runnable { done("never reported on screen") }

    init {
        AdGate.onAdScreenGone { if (presenting) done("ad screen closed") }
    }

    fun isReady(): Boolean =
        ad != null && SystemClock.elapsedRealtime() - loadedAt < MAX_AD_AGE_MS

    /** Loads one ahead, when one may show at all. Ads removed is checked first. */
    fun preload(context: Context) {
        if (loading || isReady() || !AdGate.eligible(context, GRACE_LAUNCHES)) return
        if (SystemClock.elapsedRealtime() < retryAt) return
        fetch(context.applicationContext)
    }

    /**
     * The user moved to another page in [activity]. Shows the ad when this
     * is the transition it is due on and everything allows it; otherwise
     * counts it and makes sure one is loading.
     */
    fun onTransition(activity: Activity) {
        val blocked = AdGate.blocked(activity, GRACE_LAUNCHES)
        if (blocked != null) {
            if (blocked != lastBlocked) AdGate.log(FORMAT, "page changes not counted: $blocked")
            lastBlocked = blocked
            return
        }
        lastBlocked = null
        val store = activity.getSharedPreferences(AdGate.FILE, Context.MODE_PRIVATE)
        val count = store.getInt(KEY_TRANSITIONS, 0) + 1
        if (count < TRANSITIONS_PER_AD) {
            AdGate.log(FORMAT, "page change $count of $TRANSITIONS_PER_AD" + if (isReady()) "" else " (loading one)")
        }
        val reason = when {
            count < TRANSITIONS_PER_AD -> ""
            presenting || AdGate.showing || AppOpenAds.busy || pending != null -> "another ad is up"
            AdGate.sinceLastEnd() < MIN_GAP_MS -> "under ${MIN_GAP_MS / 1000} s since the last ad"
            !isReady() -> "not loaded yet"
            else -> null
        }
        if (reason != null) {
            // Due but not possible now: the next transition tries again.
            store.edit().putInt(KEY_TRANSITIONS, count.coerceAtMost(TRANSITIONS_PER_AD)).apply()
            if (reason.isNotEmpty()) AdGate.log(FORMAT, "due, held: $reason")
            preload(activity)
            return
        }
        store.edit().putInt(KEY_TRANSITIONS, 0).apply()
        pending = activity
        // After the tap that caused it has finished and the new page is in
        // place; or when the new screen comes to the front.
        if (activity === resumedActivity) main.post { showPending(activity) }
    }

    private fun showPending(activity: Activity) {
        if (pending !== activity) return
        pending = null
        val current = ad?.takeIf { isReady() }
        val reason = when {
            activity.isFinishing || activity.isDestroyed -> "screen closed"
            activity !== resumedActivity -> "screen left"
            current == null -> "expired"
            !AdGate.eligible(activity, GRACE_LAUNCHES) -> "no longer allowed"
            AdGate.showing || AppOpenAds.busy -> "another ad is up"
            else -> null
        }
        if (reason != null || current == null) {
            requeue(activity, reason ?: "expired")
            return
        }
        ad = null
        presenting = true
        val mine = ++session
        val appContext = activity.applicationContext
        AdGate.begin(FORMAT)
        main.postDelayed(showTimeout, SHOW_TIMEOUT_MS)
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                AdGate.begin(FORMAT)
                if (mine == session) main.removeCallbacks(showTimeout)
                AdGate.log(FORMAT, "shown")
            }

            override fun onAdDismissedFullScreenContent() {
                AdGate.end(FORMAT)
                if (mine == session) done("closed")
                preload(appContext)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdGate.end(FORMAT)
                AdGate.log(FORMAT, "show failed ${error.code}: ${error.message}")
                if (mine == session) done("show failed")
                requeue(appContext, null)
            }
        }
        runCatching { current.show(activity) }.onFailure {
            AdGate.end(FORMAT)
            done("show threw: ${it.message}")
            requeue(appContext, null)
        }
    }

    /** A due ad that could not show: the next transition gets it. */
    private fun requeue(context: Context, reason: String?) {
        if (reason != null) AdGate.log(FORMAT, "due, held: $reason")
        context.getSharedPreferences(AdGate.FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TRANSITIONS, TRANSITIONS_PER_AD).apply()
        preload(context)
    }

    private fun done(reason: String) {
        if (!presenting) return
        presenting = false
        session++
        main.removeCallbacks(showTimeout)
        AdGate.end(FORMAT)
        AdGate.log(FORMAT, "ended: $reason")
    }

    private fun fetch(context: Context) {
        if (loading || isReady()) return
        ad = null
        loading = true
        Ads.initSdk(context)
        val started = SystemClock.elapsedRealtime()
        runCatching {
            InterstitialAd.load(
                context,
                AD_UNIT,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(loaded: InterstitialAd) {
                        loading = false
                        failures = 0
                        retryAt = 0L
                        ad = loaded
                        loadedAt = SystemClock.elapsedRealtime()
                        AdGate.log(FORMAT, "loaded in ${loadedAt - started} ms")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loading = false
                        retryAt = SystemClock.elapsedRealtime() +
                            RETRY_DELAYS_MS[failures.coerceAtMost(RETRY_DELAYS_MS.size - 1)]
                        failures++
                        AdGate.log(FORMAT, "load failed ${error.code}: ${error.message}")
                    }
                }
            )
        }.onFailure {
            loading = false
            AdGate.log(FORMAT, "load threw: ${it.message}")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        if (pending === activity) main.post { showPending(activity) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (pending === activity) {
            pending = null
            requeue(activity.applicationContext, "screen closed")
        }
        if (resumedActivity === activity) resumedActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
