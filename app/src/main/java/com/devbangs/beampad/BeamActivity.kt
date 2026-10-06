package com.devbangs.beampad

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
        val changed = appliedTheme != Appearance.themeFor(this) ||
            appliedContrast != Prefs(this).highContrast
        if (appliedTheme != 0 && changed) recreate()
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
