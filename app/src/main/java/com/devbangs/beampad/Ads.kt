package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlin.concurrent.thread

/**
 * Banner loading, gated on consent and on the remove-ads entitlement.
 * The banner lives at the foot of Settings, away from every control
 * surface; [start] runs as the app opens so consent is settled before any
 * ad loads.
 *
 * The slot keeps the banner's height while the first ad loads, so it
 * never pushes anything when the ad appears, and collapses if none comes
 * (retrying a few times while the page stays open).
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

    private const val FORMAT = "Banner"

    /** Further tries when a page's first banner request fails (no fill, offline). */
    private val RETRY_DELAYS_MS = longArrayOf(15_000, 45_000, 120_000)

    private var sdkStarted = false
    private var view: AdView? = null
    private val main = Handler(Looper.getMainLooper())
    private var pendingRetry: Runnable? = null

    /**
     * Gathers consent and starts the SDK, without showing anything. Run as
     * the app opens so the app open ad and the privacy options entry point
     * know where consent stands, wherever the banner lives.
     */
    fun start(activity: Activity, entitlements: Entitlements, onReady: () -> Unit = {}) {
        if (entitlements.adsRemoved) return
        Consent.gather(activity) {
            initSdk(activity)
            onReady()
        }
    }

    /**
     * Starts the SDK once per process. Called once consent allows ads, and
     * by the full-screen formats before a load, so a screen restored without
     * MainActivity (the process ended in the background) still initialises
     * first. The work itself runs off the main thread, as Google recommends,
     * so it never stalls the splash or the first frame.
     */
    fun initSdk(context: Context) {
        if (sdkStarted) return
        sdkStarted = true
        val app = context.applicationContext
        // Debug builds only: a release build must not carry test
        // device IDs, and AdMob asks for them to be removed.
        if (isDebuggable(app) && TEST_DEVICES.isNotEmpty()) {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder()
                    .setTestDeviceIds(TEST_DEVICES)
                    .build()
            )
        }
        thread(name = "beampad-ads-init", isDaemon = true) {
            runCatching { MobileAds.initialize(app) { } }
        }
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Runs [block] once consent allows ads and the SDK is started; never for
     * removed-ads users. Consent was settled as the app opened, so this is
     * usually immediate, with no second round trip.
     */
    fun whenReady(activity: Activity, entitlements: Entitlements, block: () -> Unit) {
        if (sdkStarted && Consent.canRequestAds) block()
        else start(activity, entitlements, block)
    }

    /** Shows the banner in [slot] once consent allows. Removed-ads users get nothing. */
    fun attach(activity: Activity, slot: FrameLayout, entitlements: Entitlements) {
        if (entitlements.adsRemoved) {
            detach(slot)
            return
        }
        whenReady(activity, entitlements) { show(activity, slot) }
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
            pendingRetry?.let { main.removeCallbacks(it) }
            pendingRetry = null
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
        var everLoaded = false
        var failures = 0
        banner.adListener = object : AdListener() {
            override fun onAdLoaded() {
                everLoaded = true
                failures = 0
                banner.isVisible = true
                AdGate.log(FORMAT, "loaded")
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                AdGate.log(FORMAT, "load failed ${error.code}: ${error.message}")
                // A failed refresh keeps the ad already showing; AdMob tries
                // again at the next refresh.
                if (everLoaded) return
                // Nothing yet: collapse rather than leave an empty frame,
                // and try again a little later while the page is open,
                // instead of no banner for the rest of the visit.
                banner.isVisible = false
                if (failures >= RETRY_DELAYS_MS.size) return
                val retry = Runnable {
                    if (view === banner && banner.isAttachedToWindow) banner.loadAd(AdRequest.Builder().build())
                }
                main.postDelayed(retry, RETRY_DELAYS_MS[failures++])
                pendingRetry = retry
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

    /**
     * Removes [slot]'s banner: after a purchase, or as its page closes. Only
     * the banner in that slot is destroyed: a second Settings page closing
     * must not take the banner of the one beneath it.
     */
    fun detach(slot: FrameLayout) {
        val current = view
        if (current != null && current.parent === slot) {
            pendingRetry?.let { main.removeCallbacks(it) }
            pendingRetry = null
            current.destroy()
            view = null
        }
        slot.removeAllViews()
    }

    fun pause() = view?.pause()
    fun resume() = view?.resume()
}
