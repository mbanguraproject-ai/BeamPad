package com.devbangs.beampad

import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity

/**
 * Base for every screen: applies the chosen appearance before anything is
 * inflated, draws edge to edge with bar icons that suit the background, and
 * recreates itself if the appearance changed while it was in the back stack.
 */
abstract class BeamActivity : FragmentActivity() {

    private var appliedTheme = 0
    private var appliedContrast = false
    private var appliedReducedMotion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppearance()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    /** Separate so MainActivity can run it after installing the splash screen. */
    protected fun applyAppearance() {
        appliedTheme = Appearance.themeFor(this)
        setTheme(appliedTheme)
        appliedContrast = Prefs(this).highContrast
        if (appliedContrast) theme.applyStyle(R.style.ThemeOverlay_BeamPad_HighContrast, true)
        // Keys scale when pressed unless motion is reduced (here or system-wide).
        appliedReducedMotion = Motion.reduced(this)
        theme.applyStyle(
            if (appliedReducedMotion) R.style.ThemeOverlay_BeamPad_PressStill
            else R.style.ThemeOverlay_BeamPad_PressMotion,
            true
        )
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (view == null) return
        val light = Appearance.isLight(this)
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onResume() {
        super.onResume()
        // A pass that ran out while the phone slept is announced now; posted
        // so the screen counts as resumed when it hears.
        window.decorView.post { ProPass.check(this) }
        val changed = appliedTheme != Appearance.themeFor(this) ||
            appliedContrast != Prefs(this).highContrast ||
            appliedReducedMotion != Motion.reduced(this)
        if (appliedTheme != 0 && changed) recreate()
    }

    /**
     * Every start goes through here (plain starts, result launchers,
     * fragments). A screen of another app (system settings, the voice
     * prompt, a share sheet, the store) is part of what the user is doing,
     * so coming back from it is not a return for the app open ad.
     */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (intent.component?.packageName != packageName) AppOpenAds.handOff()
        super.startActivityForResult(intent, requestCode, options)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun startIntentSenderForResult(
        intent: IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?
    ) {
        // Play's update flow.
        AppOpenAds.handOff()
        super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options)
    }

    /** Pads [view] by the system bars and cutouts, for screens that scroll as a whole. */
    protected fun padForSystemBars(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
