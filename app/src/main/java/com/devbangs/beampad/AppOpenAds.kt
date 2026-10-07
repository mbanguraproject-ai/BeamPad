package com.devbangs.beampad

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.splashscreen.SplashScreen
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.ump.UserMessagingPlatform

/**
 * App open ad, shown only as the app launches: it covers the splash screen,
 * and closing it reveals the app, which is where established apps put it.
 * Once the app is on screen it never appears, so it cannot land on top of
 * the remote mid-film, a half-typed message or the snippets vault.
 *
 * The splash waits at most [MAX_SPLASH_WAIT_MS] for an ad. Without one by
 * then the app opens as normal, and an ad that arrives late is kept for the
 * next launch (AdMob allows four hours) rather than shown over the app.
 *
 * Skipped for Pro and remove-ads users, until consent allows ads, during
 * the first [MIN_LAUNCHES] launches (onboarding and first pairing must not
 * meet an ad), when the screen is restored rather than launched, and within
 * [MIN_INTERVAL_MS] of the last one. [Ads.start] has already set up the SDK
 * and test devices by the time [onLaunch] runs.
 */
object AppOpenAds : Application.ActivityLifecycleCallbacks {

    private const val AD_UNIT = "ca-app-pub-9121922395304175/6343994525"
    private const val TAG = "BeamPadAds"

    /** AdMob expires app open ads after four hours. */
    private const val MAX_AD_AGE_MS = 4 * 60 * 60 * 1000L

    /** Longest the splash is held for an ad; past this the app opens without one. */
    private const val MAX_SPLASH_WAIT_MS = 3_000L

    /** Covers an ad that is told to show but never reports back. */
    private const val SHOW_TIMEOUT_MS = 2_000L

    /** A remote is opened many times a day; an ad on every one would drive users off. */
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L

    /** New users get a few clean launches first, as AdMob recommends. */
    private const val MIN_LAUNCHES = 3

    private const val FILE = "beampad_ads"
    private const val KEY_LAUNCHES = "launches"
    private const val KEY_LAST_SHOWN = "app_open_last_shown"

    private var ad: AppOpenAd? = null
    private var loadedAt = 0L
    private var loading = false

    /** The launch whose splash is being held for an ad, if any. */
    private var launch: Activity? = null
    private var holding = false
    private var showOnResume = false
    private var resumedActivity: Activity? = null

    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { endLaunch("no ad within $MAX_SPLASH_WAIT_MS ms") }

    /**
     * Called from MainActivity.onCreate on a real launch (not a restore),
     * after [Ads.start]. Holds the splash while an ad loads, when one may
     * be shown at all.
     */
    fun onLaunch(activity: Activity, splash: SplashScreen) {
        val store = activity.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val launches = store.getInt(KEY_LAUNCHES, 0) + 1
        store.edit().putInt(KEY_LAUNCHES, launches).apply()
        val sinceLast = System.currentTimeMillis() - store.getLong(KEY_LAST_SHOWN, 0L)

        val reason = when {
            adsRemoved(activity) -> "ads removed"
            launches <= MIN_LAUNCHES -> "launch $launches of the first $MIN_LAUNCHES"
            !consentAllows(activity) -> "no consent yet"
            sinceLast in 0 until MIN_INTERVAL_MS -> "shown ${sinceLast / 60_000} min ago"
            else -> null
        }
        if (reason != null) {
            Log.i(TAG, "app open skipped: $reason")
            return
        }

        launch = activity
        holding = true
        splash.setKeepOnScreenCondition { holding }
        main.postDelayed(giveUp, MAX_SPLASH_WAIT_MS)
        if (hasFreshAd()) showWhenReady() else fetch(activity)
    }

    private fun adsRemoved(context: Context): Boolean =
        (context.applicationContext as BeamPadApp).entitlements.adsRemoved

    /** The stored consent from earlier sessions, available without a network round trip. */
    private fun consentAllows(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).canRequestAds()

    private fun hasFreshAd(): Boolean =
        ad != null && SystemClock.elapsedRealtime() - loadedAt < MAX_AD_AGE_MS

    private fun fetch(context: Context) {
        if (loading || hasFreshAd()) return
        ad = null
        loading = true
        AppOpenAd.load(
            context.applicationContext,
            AD_UNIT,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(loaded: AppOpenAd) {
                    Log.i(TAG, "app open loaded")
                    loading = false
                    ad = loaded
                    loadedAt = SystemClock.elapsedRealtime()
                    showWhenReady()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "app open failed ${error.code}: ${error.message}")
                    loading = false
                    endLaunch("load failed")
                }
            }
        )
    }

    /** Shows the ad over the held splash, once the launch's screen is in front. */
    private fun showWhenReady() {
        val activity = launch ?: return
        if (activity.isFinishing || activity.isDestroyed) return endLaunch("screen closed")
        if (activity !== resumedActivity) {
            showOnResume = true
            return
        }
        show(activity)
    }

    private fun show(activity: Activity) {
        val current = ad ?: return endLaunch("no ad")
        ad = null
        main.removeCallbacks(giveUp)
        // The launch is handed to the ad: the screen stopping behind it is
        // not the user leaving.
        launch = null
        // Keep the splash up until the ad covers it, so the app does not
        // flash on screen first; but never longer than this.
        main.postDelayed({ release() }, SHOW_TIMEOUT_MS)

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                activity.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_SHOWN, System.currentTimeMillis())
                    .apply()
                release()
            }

            override fun onAdDismissedFullScreenContent() = release()

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "app open show failed ${error.code}: ${error.message}")
                release()
            }
        }
        current.show(activity)
    }

    private fun endLaunch(reason: String) {
        if (launch == null) return
        Log.i(TAG, "app open skipped: $reason")
        release()
    }

    /** Lets the app draw, and forgets the launch. */
    private fun release() {
        main.removeCallbacks(giveUp)
        holding = false
        launch = null
        showOnResume = false
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        if (activity === launch && showOnResume) {
            showOnResume = false
            show(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
    }

    override fun onActivityStopped(activity: Activity) {
        // Left during the splash (Home, a rotation): the launch is over. An
        // ad still loading is kept for the next launch.
        if (activity === launch) endLaunch("left during the splash")
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === launch) release()
        if (activity === resumedActivity) resumedActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
