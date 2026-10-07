package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.devbangs.beampad.databinding.FragmentRemoteBinding
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The TV remote: D-pad with volume and channel rockers, navigation and
 * transport rows. The layout (Minimal, Standard, Full) and button size
 * come from Settings, so the same screen suits a parent who wants five
 * buttons and a power user who wants all of them.
 */
class RemoteFragment : SurfaceFragment() {

    private var _ui: FragmentRemoteBinding? = null
    private val ui get() = _ui!!

    private var appliedLayout: Prefs.RemoteLayout? = null
    private var appliedScale = 0f

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentRemoteBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        ui.dpad.onKey = { k ->
            Haptics.tick(ui.dpad)
            when (k) {
                DpadView.Key.UP -> key(HidReports.KEY_UP)
                DpadView.Key.DOWN -> key(HidReports.KEY_DOWN)
                DpadView.Key.LEFT -> key(HidReports.KEY_LEFT)
                DpadView.Key.RIGHT -> key(HidReports.KEY_RIGHT)
                DpadView.Key.OK -> key(HidReports.KEY_ENTER)
            }
        }

        bind(ui.power, Action.Consumer(HidReports.CC_POWER))
        bind(ui.sleep, Action.Consumer(HidReports.CC_SLEEP))
        bind(ui.search, Action.Consumer(HidReports.CC_SEARCH))
        bind(ui.subtitles, Action.Consumer(HidReports.CC_CLOSED_CAPTION))
        bind(ui.mute, Action.Consumer(HidReports.CC_MUTE))

        bindRepeating(ui.volUp, Action.Consumer(HidReports.CC_VOLUME_UP))
        bindRepeating(ui.volDown, Action.Consumer(HidReports.CC_VOLUME_DOWN))
        bindRepeating(ui.chUp, Action.Consumer(HidReports.CC_CHANNEL_UP))
        bindRepeating(ui.chDown, Action.Consumer(HidReports.CC_CHANNEL_DOWN))

        // Escape rather than the consumer Back usage: Android TV honours it
        // more consistently, and computers treat it as Back too.
        bind(ui.back, Action.Key(HidReports.KEY_ESC.toInt()))
        bind(ui.home, Action.Consumer(HidReports.CC_HOME))
        bind(ui.menu, Action.Consumer(HidReports.CC_MENU))

        bind(ui.prev, Action.Consumer(HidReports.CC_SCAN_PREV))
        bindRepeating(ui.rewind, Action.Consumer(HidReports.CC_REWIND))
        bind(ui.playPause, Action.Consumer(HidReports.CC_PLAY_PAUSE))
        bindRepeating(ui.forward, Action.Consumer(HidReports.CC_FAST_FORWARD))
        bind(ui.next, Action.Consumer(HidReports.CC_SCAN_NEXT))

        ui.padArea.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) ui.padArea.post { fitPad() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Layout and size can change in Settings while this is in the back stack.
        applyPrefs()
    }

    override fun onConnectionChanged(connected: Boolean, changed: Boolean) {
        ui.dpad.connected = connected
    }

    override fun onVolumeKey(up: Boolean): Boolean {
        if (!connected) return false
        consumer(if (up) HidReports.CC_VOLUME_UP else HidReports.CC_VOLUME_DOWN)
        return true
    }

    private fun applyPrefs() {
        val prefs = app.prefs
        val layout = prefs.remoteLayout
        val scale = ControlSizing.scale(requireContext())
        if (layout == appliedLayout && scale == appliedScale) return
        appliedLayout = layout
        appliedScale = scale

        val minimal = layout == Prefs.RemoteLayout.MINIMAL
        val full = layout == Prefs.RemoteLayout.FULL
        ui.topRow.isVisible = !minimal
        ui.sleep.isVisible = full
        ui.subtitles.isVisible = full
        ui.chRocker.isVisible = !minimal
        ui.menu.isVisible = !minimal
        ui.prev.isVisible = full
        ui.next.isVisible = full
        listOf(ui.topRow, ui.navRow, ui.mediaRow).forEach(::alignRow)

        fun height(view: View, base: Int) = view.updateLayoutParams { height = (dp(base) * scale).roundToInt() }
        listOf(ui.power, ui.sleep, ui.search, ui.subtitles, ui.mute).forEach { height(it, 44) }
        listOf(ui.back, ui.home, ui.menu).forEach { height(it, 60) }
        listOf(ui.prev, ui.rewind, ui.playPause, ui.forward, ui.next).forEach { height(it, 52) }
        listOf(ui.volRocker, ui.chRocker).forEach { it.updateLayoutParams { width = (dp(60) * scale).roundToInt() } }
        ui.padArea.post { fitPad() }
    }

    /** The first visible key in a row sits flush with the gutter; the rest keep their gap. */
    private fun alignRow(row: ViewGroup) {
        var first = true
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (!child.isVisible) continue
            child.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = if (first) 0 else resources.getDimensionPixelSize(R.dimen.gap)
            }
            first = false
        }
    }

    /**
     * The pad takes the room the rows leave, up to a comfortable maximum;
     * the rockers match its height so the three read as one cluster.
     */
    private fun fitPad() {
        val ui = _ui ?: return
        val area = ui.padArea
        if (area.height == 0) return
        val rockers = listOf(ui.volRocker, ui.chRocker).filter { it.isVisible }
        val rockerWidth = rockers.sumOf { it.layoutParams.width + dp(12) }
        val maxByWidth = area.width - rockerWidth
        // Never larger than the area itself: a floor above that is what made
        // the pad overlap the rows on short screens and in landscape.
        val size = min(min(area.height - dp(24), maxByWidth), dp(MAX_PAD_DP))
            .coerceAtLeast(dp(MIN_PAD_DP))
            .coerceAtMost(area.height)
        ui.dpad.updateLayoutParams { width = size; height = size }
        val rockerHeight = (size * 0.86f).roundToInt()
        rockers.forEach { it.updateLayoutParams { height = rockerHeight } }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
        appliedLayout = null
    }

    private companion object {
        const val MAX_PAD_DP = 300
        const val MIN_PAD_DP = 120
    }
}
