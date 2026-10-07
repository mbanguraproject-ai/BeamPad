package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.devbangs.beampad.databinding.FragmentTrackpadBinding

/**
 * The precision trackpad: pointer with acceleration, tap and multi-finger
 * clicks, tap-and-drag, two-finger scroll, pinch to zoom and three-finger
 * gestures. Precision mode slows the pointer for small targets. The two
 * buttons below hold down while pressed, so they can drag too.
 */
class TrackpadFragment : SurfaceFragment() {

    private var _ui: FragmentTrackpadBinding? = null
    private val ui get() = _ui!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentTrackpadBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        wirePad(ui.pad, ui.padGlow)
        val baseActive = ui.pad.onTouchActive
        ui.pad.onTouchActive = { active ->
            baseActive?.invoke(active)
            // The hint is for the first touch; it steps back while in use.
            ui.hint.animate().alpha(if (active) 0f else HINT_ALPHA).setDuration(200).start()
        }

        bindMouseButton(ui.leftClick, HidReports.BUTTON_LEFT)
        bindMouseButton(ui.rightClick, HidReports.BUTTON_RIGHT)

        val icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_frame_corners)?.mutate()
        icon?.setBounds(0, 0, dp(16), dp(16))
        icon?.setTintList(ContextCompat.getColorStateList(requireContext(), R.color.chip_text))
        ui.precision.setCompoundDrawablesRelative(icon, null, null, null)
        ui.precision.setOnClickListener {
            val c = requireContext()
            if (!Features.isPro(c)) {
                ProActivity.open(c, Features.Pro.TRACKPAD)
                return@setOnClickListener
            }
            Haptics.tick(it)
            ui.pad.precision = !ui.pad.precision
            ui.precision.isSelected = ui.pad.precision
        }
    }

    override fun onResume() {
        super.onResume()
        applyPadPrefs(ui.pad)
        ui.hintText.setText(if (ui.pad.tapToClick) R.string.trackpad_hint_short else R.string.trackpad_hint_no_tap)
    }

    override fun onConnectionChanged(connected: Boolean, changed: Boolean) {
        ui.hint.alpha = HINT_ALPHA
        if (!connected) ui.padGlow.animate().alpha(0f).setDuration(160L).start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }

    private companion object {
        const val HINT_ALPHA = 1f
    }
}
