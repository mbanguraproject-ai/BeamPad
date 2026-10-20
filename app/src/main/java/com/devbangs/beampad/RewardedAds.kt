package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
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
    private const val TAG = "BeamPadAds"
    private const val FORMAT = "Rewarded"

    /** AdMob expires rewarded ads after an hour. */
    private const val MAX_AD_AGE_MS = 60 * 60 * 1000L

    enum class Outcome { EARNED, NOT_EARNED, UNAVAILABLE }

    private var ad: RewardedAd? = null
    private var loadedAt = 0L

    var loading = false
        private set

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
        if (!canOffer(activity) || loading || isReady()) return
        val app = activity.application as BeamPadApp
        val context = activity.applicationContext
        Ads.whenReady(activity, app.entitlements) { fetch(context) }
    }

    private fun fetch(context: Context) {
        if (loading || isReady()) return
        ad = null
        loading = true
        RewardedAd.load(
            context,
            AD_UNIT,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedAd) {
                    Log.i(TAG, "rewarded loaded")
                    loading = false
                    ad = loaded
                    loadedAt = SystemClock.elapsedRealtime()
                    onLoaded?.invoke(true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "rewarded failed ${error.code}: ${error.message}")
                    loading = false
                    onLoaded?.invoke(false)
                }
            }
        )
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
        fun finish(outcome: Outcome) {
            if (done) return
            done = true
            AdGate.end(FORMAT)
            AdGate.log(FORMAT, "ended: ${outcome.name.lowercase()}")
            onDone(outcome)
        }

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() =
                finish(if (earned) Outcome.EARNED else Outcome.NOT_EARNED)

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "rewarded show failed ${error.code}: ${error.message}")
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
            AdGate.end(FORMAT)
            return false
        }
        return true
    }
}
