package com.devbangs.beampad

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

/**
 * App open ad, on every launch and every return to the app, always over
 * the brand screen and never over the app itself:
 *
 * - Launch: the ad loads while the splash plays. MainActivity keeps the
 *   splash up while [holds] is true and calls [onSplashDone] when its own
 *   time is over. A ready ad shows then, one still loading gets
 *   [MAX_LATE_WAIT_MS] more, and the splash stays under the ad until it
 *   closes, so closing it reveals the app as the splash fades.
 * - Return (from Home, another app, the lock screen): a cover in the
 *   splash's colours goes over the screen the user comes back to before it
 *   draws, the ad shows over that (waiting up to [MAX_RETURN_WAIT_MS] for
 *   one still loading), and the cover fades when the ad closes.
 *
 * A new ad is loaded as soon as one closes and whenever the app goes to the
 * background, so the next launch or return usually has one ready at once.
 *
 * Skipped, with the reason logged to [AdGate]: for Pro and remove-ads
 * users, until consent allows ads, during the first [AdGate.MIN_LAUNCHES]
 * launches and onboarding, while another full-screen ad is up (a return
 * from an ad's click), and on a return from a system screen the app itself
 * opened (Bluetooth settings while pairing, voice input), which is part of
 * the user's task rather than a new visit. Every path out of a launch or a
 * return lets the app draw: a load that fails, an ad that never reports, a
 * lost callback (the AdMob screen closing is watched too) and the user
 * leaving all end it.
 */
object AppOpenAds : Application.ActivityLifecycleCallbacks {

    private const val AD_UNIT = "ca-app-pub-9121922395304175/6343994525"
    private const val FORMAT = "App open"

    /** AdMob expires app open ads after four hours. */
    private const val MAX_AD_AGE_MS = 4 * 60 * 60 * 1000L

    /**
     * Longest the splash is held past its own time for an ad still loading.
     * A cold start's first request often needs more than the splash itself.
     */
    private const val MAX_LATE_WAIT_MS = 4_000L

    /** Longest the return cover waits for an ad still loading. */
    private const val MAX_RETURN_WAIT_MS = 3_000L

    /** Covers an ad that is told to show but never reports back. */
    private const val SHOW_TIMEOUT_MS = 2_500L

    /** A return this soon after the app opened a system screen is part of that task. */
    private const val HANDOFF_WINDOW_MS = 5 * 60 * 1000L

    private const val COVER_FADE_MS = 220L
    private const val COVER_ICON_DP = 288

    private enum class Mode { LAUNCH, RETURN }

    private var ad: AppOpenAd? = null
    private var loadedAt = 0L
    private var loading = false

    /** What the current ad is for, and the screen it shows over; null when idle. */
    private var mode: Mode? = null
    private var target: Activity? = null
    private var cover: View? = null

    /** Waiting under the splash or cover for an ad still loading. */
    private var waiting = false
    private var showOnResume = false

    /** show() was called for this launch or return. */
    private var presenting = false

    /** The ad reported itself on screen. */
    private var shown = false

    /** Counts shows, so a late callback from an earlier ad cannot end a later launch or return. */
    private var session = 0

    private var resumedActivity: Activity? = null
    private var started = 0
    private var changingConfig = false
    private var everBackgrounded = false
    private var anyStarted = false
    private var handOffAt = 0L
    private var backgroundSinceHandOff = false

    private val main = Handler(Looper.getMainLooper())
    private val showTimeout = Runnable { AdGate.end(FORMAT); finish("never reported on screen") }
    private val giveUp = Runnable { finish("still loading after the wait") }

    init {
        AdGate.onAdScreenGone {
            val context = target?.applicationContext
            if (presenting && context != null) {
                AdGate.end(FORMAT)
                finish("ad screen closed")
                preload(context)
            }
        }
    }

    // ---- Launch ---------------------------------------------------------------

    /**
     * Whether MainActivity is starting as a launch: a fresh start, or the app
     * coming back after Android ended its process in the background (the
     * splash shows then too). A rotation or a re-theme is neither.
     */
    fun isLaunch(savedInstanceState: Bundle?): Boolean = savedInstanceState == null || !anyStarted

    /**
     * Called from MainActivity.onCreate on a launch ([isLaunch]), after
     * [Ads.start]. Starts loading an ad, when one may be shown at all.
     */
    fun onLaunch(activity: Activity) {
        val launches = AdGate.countLaunch(activity)
        val reason = AdGate.blocked(activity)
        if (reason != null) {
            AdGate.log(FORMAT, "launch $launches skipped: $reason")
            return
        }
        // A return still being handled (its cover is up) gives way to the launch.
        if (mode != null) finish("a launch took over")
        mode = Mode.LAUNCH
        target = activity
        if (!hasFreshAd()) fetch(activity)
    }

    /** A launch or return is under way (splash held, cover up, or its ad showing). */
    val busy: Boolean get() = mode != null

    /** Whether [activity]'s splash should stay up: until its ad has closed or is skipped. */
    fun holds(activity: Activity): Boolean = mode == Mode.LAUNCH && target === activity

    /**
     * The splash has played: show the ad if it is ready, wait a little longer
     * if it is still loading, otherwise let the app open.
     */
    fun onSplashDone(activity: Activity) {
        if (mode != Mode.LAUNCH || target !== activity) return
        if (activity.isFinishing || activity.isDestroyed) return finish("screen closed")
        when {
            hasFreshAd() -> showWhenResumed(activity)
            loading -> waitForLoad(MAX_LATE_WAIT_MS)
            else -> {
                // The load at launch failed: one more try, inside the same wait.
                fetch(activity)
                if (loading) waitForLoad(MAX_LATE_WAIT_MS) else finish("no ad")
            }
        }
    }

    // ---- Return ---------------------------------------------------------------

    /**
     * The app is about to open a screen of another app (system settings, the
     * voice prompt, a share sheet). Coming back from it is part of the same
     * task, so it does not count as a return.
     */
    fun handOff() {
        handOffAt = SystemClock.elapsedRealtime()
        backgroundSinceHandOff = false
    }

    private fun onForeground(activity: Activity) {
        val handedOff = handOffAt != 0L && SystemClock.elapsedRealtime() - handOffAt < HANDOFF_WINDOW_MS
        handOffAt = 0L
        if (mode != null) return // a launch is already handling it
        val reason = when {
            AdGate.isAdScreen(activity) || AdGate.showing -> "back to an ad"
            activity is OnboardingActivity -> "onboarding"
            activity.isFinishing -> "screen closing"
            handedOff -> "back from a screen the app opened"
            else -> AdGate.blocked(activity)
        }
        if (reason != null) {
            AdGate.log(FORMAT, "return skipped: $reason")
            return
        }
        mode = Mode.RETURN
        target = activity
        addCover(activity)
        when {
            hasFreshAd() -> showWhenResumed(activity)
            else -> {
                if (!loading) fetch(activity)
                if (loading) waitForLoad(MAX_RETURN_WAIT_MS) else finish("no ad")
            }
        }
    }

    /**
     * The splash's colours and mark over the whole window, under the ad, so
     * the ad never appears over a half-used screen. It takes the touches
     * meant for the screen beneath while it is up.
     */
    private fun addCover(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val size = Ui.dp(activity, COVER_ICON_DP)
        val view = FrameLayout(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.splash_background))
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(R.string.ad_cover_description)
            addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.ic_launcher_foreground)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            )
        }
        runCatching {
            decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cover = view
        }
    }

    private fun removeCover() {
        val view = cover ?: return
        cover = null
        val parent = view.parent as? ViewGroup ?: return
        if (Motion.reduced(view.context) || !view.isAttachedToWindow) {
            parent.removeView(view)
            return
        }
        view.isClickable = false
        view.animate().alpha(0f).setDuration(COVER_FADE_MS)
            .withEndAction { (view.parent as? ViewGroup)?.removeView(view) }
            .start()
    }

    // ---- Loading and showing --------------------------------------------------

    private fun hasFreshAd(): Boolean =
        ad != null && SystemClock.elapsedRealtime() - loadedAt < MAX_AD_AGE_MS

    /** Loads one ahead for the next launch or return, when one may show at all. */
    private fun preload(context: Context) {
        if (loading || hasFreshAd() || !AdGate.eligible(context)) return
        fetch(context)
    }

    private fun waitForLoad(maxMs: Long) {
        waiting = true
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, maxMs)
    }

    private fun fetch(context: Context) {
        if (loading || hasFreshAd()) return
        if (AdGate.adsRemoved(context) || !AdGate.consentAllows(context)) return
        ad = null
        loading = true
        Ads.initSdk(context)
        val started = SystemClock.elapsedRealtime()
        runCatching {
            AppOpenAd.load(
                context.applicationContext,
                AD_UNIT,
                AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(loaded: AppOpenAd) {
                        loading = false
                        ad = loaded
                        loadedAt = SystemClock.elapsedRealtime()
                        AdGate.log(FORMAT, "loaded in ${loadedAt - started} ms")
                        // Arrived while the splash or cover waits for it: show it now.
                        val waitingFor = target
                        if (waiting && waitingFor != null) {
                            waiting = false
                            main.removeCallbacks(giveUp)
                            if (waitingFor.isFinishing || waitingFor.isDestroyed) finish("screen closed")
                            else showWhenResumed(waitingFor)
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loading = false
                        AdGate.log(FORMAT, "load failed ${error.code}: ${error.message}")
                        if (waiting) finish("load failed")
                    }
                }
            )
        }.onFailure {
            loading = false
            AdGate.log(FORMAT, "load threw: ${it.message}")
        }
    }

    private fun showWhenResumed(activity: Activity) {
        if (activity !== resumedActivity) {
            showOnResume = true
            return
        }
        show(activity)
    }

    private fun show(activity: Activity) {
        val current = ad?.takeIf { hasFreshAd() } ?: return finish("no ad")
        // A purchase can land while the ad loads; consent can be withdrawn.
        if (AdGate.adsRemoved(activity) || !AdGate.consentAllows(activity)) return finish("no longer allowed")
        if (AdGate.showing) return finish("another ad is up")
        ad = null
        presenting = true
        shown = false
        val mine = ++session
        val what = if (mode == Mode.LAUNCH) "launch" else "return"
        val appContext = activity.applicationContext
        AdGate.begin(FORMAT)
        main.postDelayed(showTimeout, SHOW_TIMEOUT_MS)

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                // Also after a show timeout: the ad is up, whatever came first.
                AdGate.begin(FORMAT)
                if (mine != session) return
                main.removeCallbacks(showTimeout)
                if (presenting) shown = true
                AdGate.log(FORMAT, "shown on $what")
            }

            override fun onAdDismissedFullScreenContent() {
                AdGate.end(FORMAT)
                if (mine == session) finish("closed")
                preload(appContext)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdGate.end(FORMAT)
                AdGate.log(FORMAT, "show failed ${error.code}: ${error.message}")
                if (mine == session) finish("show failed")
                preload(appContext)
            }
        }
        runCatching { current.show(activity) }.onFailure {
            AdGate.end(FORMAT)
            finish("show threw: ${it.message}")
        }
    }

    /** Ends the launch or return: the splash lets go, the cover fades, the app is in charge. */
    private fun finish(reason: String) {
        val ending = mode ?: return
        AdGate.log(FORMAT, "${ending.name.lowercase()} ended: $reason")
        session++
        main.removeCallbacks(showTimeout)
        main.removeCallbacks(giveUp)
        removeCover()
        mode = null
        target = null
        showOnResume = false
        presenting = false
        shown = false
        waiting = false
    }

    // ---- Lifecycle ------------------------------------------------------------

    override fun onActivityStarted(activity: Activity) {
        anyStarted = true
        started++
        if (started == 1) {
            val rotated = changingConfig
            changingConfig = false
            if (!rotated && everBackgrounded) onForeground(activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        // The other app's screen was a dialog over this one (enable
        // Bluetooth, a share sheet): the app never left, so a later real
        // return must not be mistaken for coming back from it.
        if (handOffAt != 0L && !backgroundSinceHandOff && !AdGate.isAdScreen(activity)) handOffAt = 0L
        if (activity !== target) return
        when {
            showOnResume -> {
                showOnResume = false
                show(activity)
            }
            // The screen under the ad is back on top, so the ad has gone,
            // whether or not it said so.
            shown -> {
                AdGate.end(FORMAT)
                finish("closed")
                preload(activity.applicationContext)
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) {
            if (activity.isChangingConfigurations) {
                changingConfig = true
            } else {
                everBackgrounded = true
                backgroundSinceHandOff = true
                // Ready for the return.
                preload(activity.applicationContext)
            }
        }
        // Left before the ad came (Home during the splash or the cover): this
        // launch or return is over, and an ad still loading is kept for the
        // next one. The ad covering the screen also stops it; that one ends
        // when the ad reports.
        if (activity === target && !presenting) finish("left before the ad")
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === target) {
            if (presenting) AdGate.end(FORMAT)
            finish("screen destroyed")
        }
        if (activity === resumedActivity) resumedActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Android ended the process in the background and is bringing back a
        // screen other than the main one (Settings, an editor): that is a
        // return. MainActivity coming back this way is a launch instead.
        if (savedInstanceState != null && !anyStarted && activity !is MainActivity) everBackgrounded = true
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
