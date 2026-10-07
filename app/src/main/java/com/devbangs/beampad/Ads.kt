package com.devbangs.beampad

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration

/**
 * Banner loading, gated on consent and on the remove-ads entitlement.
 * The banner lives in Settings, away from every control surface; [start]
 * runs as the app opens so consent is settled before any ad loads.
 *
 * The slot has zero height until an ad actually arrives, so a failed or
 * skipped load leaves no empty gap in the layout.
 */
object Ads {

    private const val BANNER_UNIT = "ca-app-pub-9121922395304175/7354060579"

    /**
     * Devices that receive test ads instead of live ones.
     *
     * Live ads on a development device count as invalid traffic and are the
     * usual reason AdMob accounts get suspended before launch. Add the hash
     * that the SDK logs on first ad request: look for "Use RequestConfiguration"
     * in logcat.
     */
    private val TEST_DEVICES = listOf("072B81DAA6F3356B9674DB7F001A957F")

    private var sdkStarted = false
    private var view: AdView? = null

    /**
     * Gathers consent and starts the SDK, without showing anything. Run as
     * the app opens so the app open ad and the privacy options entry point
     * know where consent stands, wherever the banner lives.
     */
    fun start(activity: Activity, entitlements: Entitlements, onReady: () -> Unit = {}) {
        if (entitlements.adsRemoved) return
        Consent.gather(activity) {
            if (!sdkStarted) {
                // Debug builds only: a release build must not carry test
                // device IDs, and AdMob asks for them to be removed.
                if (isDebuggable(activity) && TEST_DEVICES.isNotEmpty()) {
                    MobileAds.setRequestConfiguration(
                        RequestConfiguration.Builder()
                            .setTestDeviceIds(TEST_DEVICES)
                            .build()
                    )
                }
                MobileAds.initialize(activity) { }
                sdkStarted = true
            }
            onReady()
        }
    }

    private fun isDebuggable(activity: Activity): Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Shows the banner in [slot] once consent allows. Removed-ads users get nothing. */
    fun attach(activity: Activity, slot: FrameLayout, entitlements: Entitlements) {
        if (entitlements.adsRemoved) {
            detach(slot)
            return
        }
        // Consent was settled as the app opened; no second round trip.
        if (sdkStarted && Consent.canRequestAds) show(activity, slot)
        else start(activity, entitlements) { show(activity, slot) }
    }

    private fun show(activity: Activity, slot: FrameLayout) {
        // The banner is sized to the slot. Before layout the slot has no
        // width, and sizing to the whole window instead made the banner
        // wider than the card it sits in, so its right side was cut off
        // (which AdMob also forbids). Wait for the real width.
        if (slot.width == 0) {
            slot.doOnLayout { if (!activity.isFinishing && !activity.isDestroyed) show(activity, slot) }
            return
        }
        // The AdView belongs to the Activity that created it. This object
        // outlives that Activity, so a view held from a previous instance is
        // attached to a dead slot and renders nothing. Rebuild it instead of
        // returning early.
        val existing = view
        if (existing != null) {
            if (existing.parent === slot) return
            (existing.parent as? android.view.ViewGroup)?.removeView(existing)
            existing.destroy()
            view = null
        }

        val banner = AdView(activity).apply {
            adUnitId = BANNER_UNIT
            setAdSize(adaptiveSize(activity, slot))
        }
        view = banner

        slot.removeAllViews()
        slot.addView(
            banner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        banner.adListener = object : AdListener() {
            override fun onAdLoaded() {
                android.util.Log.i("BeamPadAds", "loaded")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                android.util.Log.w("BeamPadAds", "failed ${error.code}: ${error.message}")
                // Collapse the slot: an empty banner frame looks like a bug.
                slot.removeAllViews()
                view?.destroy()
                view = null
            }
        }
        banner.loadAd(AdRequest.Builder().build())
    }

    /**
     * Anchored adaptive banner: the SDK picks the height for this width,
     * the slot's own width inside its padding, so the creative always fits.
     */
    private fun adaptiveSize(activity: Activity, slot: FrameLayout): AdSize {
        val density = activity.resources.displayMetrics.density
        val widthPx = slot.width - slot.paddingLeft - slot.paddingRight
        val widthDp = (widthPx / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
    }

    /** Called after a successful purchase: removes the banner immediately. */
    fun detach(slot: FrameLayout) {
        view?.destroy()
        view = null
        slot.removeAllViews()
    }

    fun pause() = view?.pause()
    fun resume() = view?.resume()
}
