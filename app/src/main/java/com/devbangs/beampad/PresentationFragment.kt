package com.devbangs.beampad

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.devbangs.beampad.databinding.FragmentPresentationBinding

/**
 * Presentation mode: large Next and Previous, start, black screen and end,
 * a pointer pad, and a talk timer. Page Down and Page Up drive PowerPoint,
 * Google Slides, Keynote and PDF viewers alike. The phone's volume buttons
 * also change slides here, so it works as a clicker without looking.
 */
class PresentationFragment : SurfaceFragment() {

    private var _ui: FragmentPresentationBinding? = null
    private val ui get() = _ui!!

    /** Elapsed time banked before the current run, and when the run started (0 when paused). */
    private var banked = 0L
    private var runningSince = 0L

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

        bind(ui.next, Action.Key(HidReports.KEY_PAGE_DOWN.toInt()))
        bind(ui.prev, Action.Key(HidReports.KEY_PAGE_UP.toInt()))
        bind(ui.black, Action.Key(HidReports.KEY_B.toInt()))
        bind(ui.end, Action.Key(HidReports.KEY_ESC.toInt()))

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
            renderTimer()
        }
        renderTimer()
        if (runningSince != 0L) ui.timer.post(tick)
    }

    override fun onResume() {
        super.onResume()
        applyPadPrefs(ui.pointer)
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
        val running = runningSince != 0L
        ui.timerToggle.setImageResource(if (running) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        ui.timerToggle.contentDescription =
            getString(if (running) R.string.present_timer_pause else R.string.present_timer_start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_BANKED, banked)
        outState.putLong(KEY_SINCE, runningSince)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui?.timer?.removeCallbacks(tick)
        _ui = null
    }

    private companion object {
        const val KEY_BANKED = "banked"
        const val KEY_SINCE = "since"
    }
}
