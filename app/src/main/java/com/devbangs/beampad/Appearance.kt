package com.devbangs.beampad

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

/** The four appearance options from Settings. */
enum class Appearance(val labelRes: Int) {
    SYSTEM(R.string.appearance_system),
    LIGHT(R.string.appearance_light),
    DARK(R.string.appearance_dark),
    BLACK(R.string.appearance_black);

    companion object {
        /** The theme to apply for the saved choice, resolving System against the phone. */
        fun themeFor(context: Context): Int = when (resolve(context)) {
            LIGHT -> R.style.Theme_BeamPad_Light
            BLACK -> R.style.Theme_BeamPad_Black
            else -> R.style.Theme_BeamPad
        }

        fun isLight(context: Context): Boolean = resolve(context) == LIGHT

        private fun resolve(context: Context): Appearance {
            val chosen = Prefs(context).appearance
            if (chosen != SYSTEM) return chosen
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
        }
    }
}

/** A colour from the current theme's tokens, e.g. [R.attr.bpAccent]. */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.data
}

/**
 * Motion is skipped when the user turned it off here or removed animations
 * system-wide, which Android exposes as an animator scale of zero.
 */
object Motion {
    fun reduced(context: Context): Boolean {
        if (Prefs(context).reduceMotion) return true
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE
            )
        }.getOrDefault(1f)
        return scale == 0f
    }

    /** Duration to use for an animation, or 0 when motion is reduced. */
    fun duration(context: Context, millis: Long): Long = if (reduced(context)) 0L else millis
}

/**
 * Haptics in one place, so the on/off switch and strength apply everywhere.
 * Light ticks for controls; [confirm] is the medium pulse the blueprint asks
 * for on connection and macro completion.
 */
object Haptics {

    fun tick(view: View) {
        val prefs = Prefs(view.context)
        if (!prefs.haptics) return
        when (prefs.hapticStrength) {
            Prefs.HapticStrength.LIGHT ->
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Prefs.HapticStrength.MEDIUM ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Prefs.HapticStrength.STRONG ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun confirm(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.haptics) return
        val vibrator = vibrator(context) ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        } else {
            VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}

/**
 * One sizing rule for control surfaces, so "Button size" and "Large
 * controls" mean the same on the remote, keyboard, media and panels.
 */
object ControlSizing {
    fun scale(context: Context): Float {
        val prefs = Prefs(context)
        return prefs.buttonSize.scale * if (prefs.largeControls) LARGE_BOOST else 1f
    }

    /** [dp] scaled and converted to pixels. */
    fun px(context: Context, dp: Float): Int =
        (dp * scale(context) * context.resources.displayMetrics.density).toInt()

    private const val LARGE_BOOST = 1.15f
}
