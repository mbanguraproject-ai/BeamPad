package com.devbangs.beampad

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.window.layout.WindowMetricsCalculator
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration

/**
 * Banner loading, gated on consent and on the remove-ads entitlement.
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

    fun attach(activity: Activity, slot: FrameLayout, entitlements: Entitlements) {
        if (entitlements.adsRemoved) {
            detach(slot)
            return
        }

        Consent.gather(activity) {
            if (!sdkStarted) {
                if (TEST_DEVICES.isNotEmpty()) {
                    MobileAds.setRequestConfiguration(
                        RequestConfiguration.Builder()
                            .setTestDeviceIds(TEST_DEVICES)
                            .build()
                    )
                }
                MobileAds.initialize(activity) { }
                sdkStarted = true
            }
            show(activity, slot)
        }
    }

    private fun show(activity: Activity, slot: FrameLayout) {
        if (view != null) return

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
            override fun onAdFailedToLoad(error: LoadAdError) {
                // Collapse the slot: an empty banner frame looks like a bug.
                slot.removeAllViews()
                view?.destroy()
                view = null
            }
        }
        banner.loadAd(AdRequest.Builder().build())
    }

    /**
     * Anchored adaptive banner: the SDK picks the height for this width.
     * Uses WindowMetrics rather than the deprecated Display.getMetrics.
     */
    private fun adaptiveSize(activity: Activity, slot: FrameLayout): AdSize {
        val density = activity.resources.displayMetrics.density
        val windowWidthPx = WindowMetricsCalculator.getOrCreate()
            .computeCurrentWindowMetrics(activity)
            .bounds
            .width()
        val widthPx = if (slot.width > 0) slot.width else windowWidthPx
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
