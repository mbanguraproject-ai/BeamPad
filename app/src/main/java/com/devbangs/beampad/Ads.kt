package com.devbangs.beampad

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.window.layout.WindowMetricsCalculator
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

/**
 * Banner loading, gated on consent and on the remove-ads entitlement.
 *
 * The slot has zero height until an ad actually arrives, so a failed or
 * skipped load leaves no empty gap in the layout.
 */
object Ads {

    // Google's public test unit. Replace with the real unit before release;
    // serving live ads on a test unit earns nothing, and testing against a
    // live unit is an invalid-traffic violation.
    private const val BANNER_UNIT = "ca-app-pub-3940256099942544/9214589741"

    private var sdkStarted = false
    private var view: AdView? = null

    fun attach(activity: Activity, slot: FrameLayout, entitlements: Entitlements) {
        if (entitlements.adsRemoved) {
            detach(slot)
            return
        }

        Consent.gather(activity) {
            if (!sdkStarted) {
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
