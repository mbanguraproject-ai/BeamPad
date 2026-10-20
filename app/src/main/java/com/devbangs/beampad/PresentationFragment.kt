package com.devbangs.beampad

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import com.devbangs.beampad.databinding.FragmentPresentationBinding

/**
 * Presentation mode: large Next and Previous, start, black screen and end,
 * a pointer pad, and a talk timer. Page Down and Page Up drive PowerPoint,
 * Google Slides, Keynote and PDF viewers alike. The phone's volume buttons
 * also change slides here, so it works as a clicker without looking.
 *
 * Opens in portrait, held like a clicker in one hand; the rotate button
 * turns it sideways for two thumbs and is remembered. The timer can take
 * the talk's length: it buzzes a minute before and again at the time, so
 * the speaker never has to look at it.
 */
class PresentationFragment : SurfaceFragment() {

    override val firstTip = R.string.tip_presentation

    private var _ui: FragmentPresentationBinding? = null
    private val ui get() = _ui!!

    /** Elapsed time banked before the current run, and when the run started (0 when paused). */
    private var banked = 0L
    private var runningSince = 0L

    /** Alerts already given for this run: 1 after the one-minute warning, 2 after time's up. */
    private var alerted = 0

    private val prefs get() = app.prefs

    private val tick = object : Runnable {
        override fun run() {
            renderTimer()
            if (runningSince != 0L) _ui?.timer?.postDelayed(this, 250)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentPresentationBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        banked = state?.getLong(KEY_BANKED) ?: 0L
        runningSince = state?.getLong(KEY_SINCE) ?: 0L
        alerted = state?.getInt(KEY_ALERTED) ?: 0

        bind(ui.next, Action.Key(HidReports.KEY_PAGE_DOWN.toInt()))
        bind(ui.prev, Action.Key(HidReports.KEY_PAGE_UP.toInt()))
        bind(ui.black, Action.Key(HidReports.KEY_B.toInt()))
        bind(ui.end, Action.Key(HidReports.KEY_ESC.toInt()))

        // Laser pointer: PowerPoint toggles it with Ctrl+L, Google Slides
        // with L. Either way the pointer pad then moves the laser dot.
        ui.laser.setOnClickListener {
            Haptics.tick(it)
            perform(Action.Key(HidReports.KEY_L.toInt(), HidReports.MOD_LEFT_CTRL.toInt()))
            if (connected) showLaserHintOnce()
        }
        ui.laser.setOnLongClickListener {
            Haptics.tick(it)
            perform(Action.Key(HidReports.KEY_L.toInt()))
            true
        }

        // Portrait unless the user turned it sideways. Held only while this
        // surface is showing. Large screens rotate freely (Android ignores
        // the request there anyway), so they get no button.
        applyOrientation()
        ui.rotate.isVisible = !largeScreen()
        renderRotate()
        ui.rotate.setOnClickListener {
            Haptics.tick(it)
            prefs.presentLandscape = !prefs.presentLandscape
            // Rotating recreates the screen; the timer is saved and carries on.
            applyOrientation()
        }

        ui.timerAlert.setOnClickListener {
            Haptics.tick(it)
            pickAlert()
        }

        // Starting the show starts the clock, if it is not already running.
        ui.start.setOnClickListener {
            Haptics.tick(it)
            perform(Action.Key(HidReports.KEY_F5.toInt()))
            if (runningSince == 0L && connected) toggleTimer()
        }

        wirePad(ui.pointer, ui.pointerGlow)
        ui.timerToggle.setOnClickListener {
            Haptics.tick(it)
            toggleTimer()
        }
        ui.timerReset.setOnClickListener {
            Haptics.tick(it)
            banked = 0L
            runningSince = if (runningSince != 0L) SystemClock.elapsedRealtime() else 0L
            alerted = 0
            renderTimer()
        }
        renderTimer()
        if (runningSince != 0L) ui.timer.post(tick)
    }

    override fun onResume() {
        super.onResume()
        applyPadPrefs(ui.pointer)
        scaleKeys(ui.root)
    }

    override fun onVolumeKey(up: Boolean): Boolean {
        if (!connected) return false
        Haptics.tick(ui.next)
        key(if (up) HidReports.KEY_PAGE_UP else HidReports.KEY_PAGE_DOWN)
        return true
    }

    private fun elapsed(): Long =
        banked + if (runningSince != 0L) SystemClock.elapsedRealtime() - runningSince else 0L

    private fun toggleTimer() {
        if (runningSince == 0L) {
            runningSince = SystemClock.elapsedRealtime()
            ui.timer.post(tick)
        } else {
            banked = elapsed()
            runningSince = 0L
        }
        renderTimer()
    }

    private fun renderTimer() {
        val ui = _ui ?: return
        val total = elapsed() / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        ui.timer.text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        // Hours need the room the buttons beside it leave.
        ui.timer.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (h > 0) 30f else 38f)
        val running = runningSince != 0L
        checkAlerts(total, running)
        ui.timerToggle.setImageResource(if (running) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        ui.timerToggle.contentDescription =
            getString(if (running) R.string.present_timer_pause else R.string.present_timer_start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_BANKED, banked)
        outState.putLong(KEY_SINCE, runningSince)
        outState.putInt(KEY_ALERTED, alerted)
    }

    private fun largeScreen(): Boolean = resources.configuration.smallestScreenWidthDp >= 600

    private fun applyOrientation() {
        activity?.requestedOrientation = when {
            largeScreen() -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            prefs.presentLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun renderRotate() {
        val label = getString(
            if (prefs.presentLandscape) R.string.present_rotate_portrait else R.string.present_rotate_landscape
        )
        ui.rotate.contentDescription = label
        TooltipCompat.setTooltipText(ui.rotate, label)
    }

    private fun pickAlert() {
        val context = context ?: return
        val choices = ALERT_MINUTES.map { minutes ->
            Sheets.Choice(
                minutes,
                if (minutes == 0) getString(R.string.present_alert_off)
                else getString(R.string.present_alert_minutes, minutes)
            )
        }
        Sheets.choose(context, getString(R.string.present_alert), choices,
            prefs.presentAlertMinutes, getString(R.string.present_alert_body)) { minutes ->
            prefs.presentAlertMinutes = minutes
            // A new length re-arms both alerts, unless already past them.
            alerted = 0
            val total = elapsed() / 1000
            if (minutes > 0) {
                if (total >= minutes * 60L - 60) alerted = 1
                if (total >= minutes * 60L) alerted = 2
            }
            renderTimer()
        }
    }

    /**
     * A minute before the talk's length: two short buzzes and the time
     * turns to the warning colour. At the length: a long buzz. Only while
     * the clock runs, once each per run (reset re-arms them).
     */
    private fun checkAlerts(totalSeconds: Long, running: Boolean) {
        val ui = _ui ?: return
        val limit = prefs.presentAlertMinutes * 60L
        val context = ui.root.context
        val label = if (limit > 0) {
            getString(R.string.present_timer_of, prefs.presentAlertMinutes)
        } else {
            getString(R.string.present_timer)
        }
        // Runs four times a second: only touch the views when something changed.
        if (ui.timerLabel.text.toString() != label) ui.timerLabel.text = label
        ui.timerAlert.isSelected = limit > 0
        ui.timerAlert.imageTintList = android.content.res.ColorStateList.valueOf(
            context.themeColor(if (limit > 0) R.attr.bpAccent2 else R.attr.bpTextDim)
        )
        val warning = limit > 0 && totalSeconds >= limit - 60
        ui.timer.setTextColor(context.themeColor(if (warning) R.attr.bpDanger else R.attr.bpText))
        if (limit <= 0 || !running) return
        if (alerted < 1 && totalSeconds >= limit - 60 && totalSeconds < limit) {
            alerted = 1
            Haptics.alert(context, timeUp = false)
        }
        if (alerted < 2 && totalSeconds >= limit) {
            alerted = 2
            Haptics.alert(context, timeUp = true)
        }
    }

    private fun showLaserHintOnce() {
        val store = requireContext().getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        if (store.getBoolean(KEY_LASER_HINT, false)) return
        store.edit().putBoolean(KEY_LASER_HINT, true).apply()
        android.widget.Toast.makeText(requireContext(), R.string.present_laser_hint, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        // Leaving Presentation gives the phone its own orientation back; a
        // rotation (the activity being recreated) keeps landscape.
        activity?.takeUnless { it.isChangingConfigurations }
            ?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        super.onDestroyView()
        _ui?.timer?.removeCallbacks(tick)
        _ui = null
    }

    private companion object {
        const val KEY_BANKED = "banked"
        const val KEY_SINCE = "since"
        const val KEY_ALERTED = "alerted"
        val ALERT_MINUTES = listOf(0, 5, 10, 15, 20, 30, 45, 60)
        const val KEY_LASER_HINT = "laser_hint_shown"
    }
}
