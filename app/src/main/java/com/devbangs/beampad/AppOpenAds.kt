package com.devbangs.beampad

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

/**
 * App open ad, shown when the user comes back to BeamPad after time away.
 *
 * Separate from the banner in [Ads] and leans on it for setup: nothing loads
 * until [Consent] allows ads, and by then [Ads.start] has initialised the
 * SDK and registered the test devices.
 *
 * Never on a cold launch (the control surface must appear at once), never
 * on a quick switch, never more than once per [MIN_INTERVAL_MS], never over
 * the connection flow or the snippets vault (see
 * [MainActivity.allowsAppOpenAd]), and never for Pro or remove-ads users.
 */
object AppOpenAds : Application.ActivityLifecycleCallbacks {

    private const val AD_UNIT = "ca-app-pub-9121922395304175/6343994525"
    private const val TAG = "BeamPadAds"

    /** AdMob expires app open ads after four hours. */
    private const val MAX_AD_AGE_MS = 4 * 60 * 60 * 1000L

    /** Shorter trips (copying a code, answering a message) are not a return. */
    private const val MIN_AWAY_MS = 30 * 1000L

    /** A remote is backgrounded constantly; an ad on every return would drive users off. */
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L

    /** Retries after MainActivity opens, while consent is still being gathered. */
    private val CONSENT_RETRIES_MS = listOf(2_000L, 6_000L, 15_000L)

    /** Covers a trip to Bluetooth settings or the TV's pairing screen. */
    private const val CONNECTION_FLOW_GRACE_MS = 10 * 60 * 1000L

    private var ad: AppOpenAd? = null
    private var loadedAt = 0L
    private var loading = false
    private var showing = false
    private var lastShownAt = 0L

    private val main = Handler(Looper.getMainLooper())

    private var startedActivities = 0
    private var changingConfig = false
    private var backgroundedAt = 0L
    private var skipReturnsUntil = 0L

    /**
     * Call before sending the user out of the app as part of connecting:
     * coming back from that must land on the pairing state, not an ad.
     */
    fun skipNextReturn() {
        skipReturnsUntil = SystemClock.elapsedRealtime() + CONNECTION_FLOW_GRACE_MS
    }

    private fun adsAllowed(context: Context): Boolean =
        Consent.canRequestAds &&
            !(context.applicationContext as BeamPadApp).entitlements.adsRemoved

    private fun hasFreshAd(now: Long): Boolean =
        ad != null && now - loadedAt < MAX_AD_AGE_MS

    private fun fetch(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (loading || hasFreshAd(now) || !adsAllowed(context)) return
        ad = null
        loading = true
        AppOpenAd.load(
            context.applicationContext,
            AD_UNIT,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(loaded: AppOpenAd) {
                    Log.i(TAG, "app open loaded")
                    ad = loaded
                    loadedAt = SystemClock.elapsedRealtime()
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "app open failed ${error.code}: ${error.message}")
                    loading = false
                }
            }
        )
    }

    private fun onReturn(activity: Activity) {
        val now = SystemClock.elapsedRealtime()
        val awayFor = now - backgroundedAt
        val skip = now < skipReturnsUntil
        skipReturnsUntil = 0L

        // backgroundedAt is 0 on a cold launch: there was no time away.
        val reason = when {
            backgroundedAt == 0L -> "cold launch"
            awayFor < MIN_AWAY_MS -> "away ${awayFor / 1000}s, under ${MIN_AWAY_MS / 1000}s"
            skip -> "back from Bluetooth, pairing or permissions"
            showing -> "already showing"
            lastShownAt != 0L && now - lastShownAt < MIN_INTERVAL_MS ->
                "shown ${(now - lastShownAt) / 60_000} min ago, under ${MIN_INTERVAL_MS / 60_000} min"
            activity !is MainActivity -> "not the main screen"
            !(activity as MainActivity).allowsAppOpenAd() -> "snippets tab or connecting"
            !adsAllowed(activity) -> "no consent yet, or ads removed"
            !hasFreshAd(now) -> if (loading) "still loading" else "no ad loaded"
            else -> null
        }
        if (reason != null) {
            Log.i(TAG, "app open skipped: $reason")
            if (!adsAllowed(activity)) ad = null else fetch(activity)
            return
        }
        val current = ad ?: return

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastShownAt = SystemClock.elapsedRealtime()
            }

            override fun onAdDismissedFullScreenContent() {
                showing = false
                fetch(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "app open show failed ${error.code}: ${error.message}")
                showing = false
                fetch(activity)
            }
        }
        ad = null
        showing = true
        current.show(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0 && !changingConfig) onReturn(activity)
        changingConfig = false
        startedActivities++
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        fetch(activity)
        // Consent is gathered asynchronously just after MainActivity opens,
        // so the first attempt above is usually too early. Try again
        // shortly, so an ad is ready for the first return rather than only
        // after the user has already left once. Each retry is a no-op once
        // an ad is loaded or loading.
        val app = activity.applicationContext
        CONSENT_RETRIES_MS.forEach { delay -> main.postDelayed({ fetch(app) }, delay) }
    }

    override fun onActivityStopped(activity: Activity) {
        // A rotation stops and restarts the Activity; that is not a trip away.
        if (activity.isChangingConfigurations) changingConfig = true
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !changingConfig) {
            backgroundedAt = SystemClock.elapsedRealtime()
            fetch(activity)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
