package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.UserMessagingPlatform

/**
 * The rewarded ad behind [ProPass]: the user chooses to watch it on the Pro
 * page and gets a day of Pro features for watching it through. It never
 * plays on its own.
 *
 * Offered only while ads may be requested, and never to anyone with ads
 * removed (Pro, or the remove-ads purchase). The Pro page loads one ahead,
 * so a tap usually plays at once. The reward is granted the moment AdMob
 * reports it earned, before the ad closes, so leaving the app from the ad
 * cannot lose it.
 */
object RewardedAds {

    private const val AD_UNIT = "ca-app-pub-9121922395304175/7281845718"
    private const val FORMAT = "Rewarded"

    /** AdMob expires rewarded ads after an hour. */
    private const val MAX_AD_AGE_MS = 60 * 60 * 1000L

    enum class Outcome { EARNED, NOT_EARNED, UNAVAILABLE }

    private var ad: RewardedAd? = null
    private var loadedAt = 0L
    private var loadingSince = 0L
    private var loadId = 0

    var loading = false
        private set

    private val main = Handler(Looper.getMainLooper())

    /**
     * Ends the ad on screen, if any, with what it earned. Kept so that the
     * AdMob screen closing ends it too when the ad never reports its close.
     */
    private var finishShowing: (() -> Unit)? = null

    init {
        AdGate.onAdScreenGone { finishShowing?.invoke() }
    }

    /** A load AdMob has not answered for [AdGate.LOAD_STUCK_MS] is given up, so it cannot block loading for good. */
    private fun stillLoading(): Boolean {
        if (loading && SystemClock.elapsedRealtime() - loadingSince > AdGate.LOAD_STUCK_MS) {
            loading = false
            AdGate.log(FORMAT, "load gave no answer; trying again")
        }
        return loading
    }

    /**
     * Told when a load ends, with whether an ad arrived. Set by the Pro page
     * while it is open and cleared when it closes.
     */
    var onLoaded: ((Boolean) -> Unit)? = null

    /** Whether the Pro page may offer the ad at all. Checks ads removed first, so removed-ads users never touch the SDK. */
    fun canOffer(context: Context): Boolean {
        val app = context.applicationContext as BeamPadApp
        return !app.entitlements.adsRemoved &&
            UserMessagingPlatform.getConsentInformation(context).canRequestAds()
    }

    fun isReady(): Boolean =
        ad != null && SystemClock.elapsedRealtime() - loadedAt < MAX_AD_AGE_MS

    /** Loads one ad ahead, once consent allows. Nothing happens if one is ready or loading. */
    fun load(activity: Activity) {
        if (!canOffer(activity) || stillLoading() || isReady()) return
        val app = activity.application as BeamPadApp
        val context = activity.applicationContext
        Ads.whenReady(activity, app.entitlements) { fetch(context) }
    }

    private fun fetch(context: Context) {
        if (stillLoading() || isReady()) return
        ad = null
        loading = true
        loadingSince = SystemClock.elapsedRealtime()
        val id = ++loadId
        runCatching {
            RewardedAd.load(
                context,
                AD_UNIT,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(loaded: RewardedAd) {
                        if (id != loadId) return
                        AdGate.log(FORMAT, "loaded")
                        loading = false
                        ad = loaded
                        loadedAt = SystemClock.elapsedRealtime()
                        onLoaded?.invoke(true)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (id != loadId) return
                        AdGate.log(FORMAT, "load failed ${error.code}: ${error.message}")
                        loading = false
                        onLoaded?.invoke(false)
                    }
                }
            )
        }.onFailure {
            // Never a crash on the Pro page: the page offers "no ad right now".
            loading = false
            AdGate.log(FORMAT, "load threw: ${it.message}")
            onLoaded?.invoke(false)
        }
    }

    /**
     * Plays the ready ad over [activity] and returns true, or returns false
     * when none is ready or ads may no longer be shown. [onDone] runs once, when the ad closes or fails to
     * show, with what came of it. No next ad is loaded: the pass it earns
     * lasts longer than an ad stays valid.
     */
    fun show(activity: Activity, onDone: (Outcome) -> Unit): Boolean {
        // Checked again here: a purchase can turn up while the ad loads.
        if (!canOffer(activity)) return false
        val current = ad?.takeIf { isReady() } ?: return false
        ad = null
        val context = activity.applicationContext
        var earned = false
        var done = false
        var onScreen = false
        // Ad never reported on screen: free the gate for the other formats.
        // The outcome still waits for the ad, which may yet show late.
        val showTimeout = Runnable { if (!onScreen && !done) AdGate.end(FORMAT) }
        fun finish(outcome: Outcome) {
            if (done) return
            done = true
            main.removeCallbacks(showTimeout)
            finishShowing = null
            AdGate.end(FORMAT)
            AdGate.log(FORMAT, "ended: ${outcome.name.lowercase()}")
            onDone(outcome)
        }
        finishShowing = { finish(if (earned) Outcome.EARNED else Outcome.NOT_EARNED) }

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                onScreen = true
                main.removeCallbacks(showTimeout)
                AdGate.begin(FORMAT)
                AdGate.log(FORMAT, "shown")
            }

            override fun onAdDismissedFullScreenContent() =
                finish(if (earned) Outcome.EARNED else Outcome.NOT_EARNED)

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdGate.log(FORMAT, "show failed ${error.code}: ${error.message}")
                finish(if (earned) Outcome.EARNED else Outcome.UNAVAILABLE)
            }
        }
        // Marked as up before it shows, so no other format can stack on it.
        AdGate.begin(FORMAT)
        val started = runCatching {
            current.show(activity) {
                if (!earned) {
                    earned = true
                    ProPass.grant(context)
                }
            }
        }.isSuccess
        if (!started) {
            finishShowing = null
            AdGate.end(FORMAT)
            return false
        }
        main.postDelayed(showTimeout, AdGate.SHOW_TIMEOUT_MS)
        return true
    }
}
