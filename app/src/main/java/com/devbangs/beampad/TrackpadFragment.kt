package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.devbangs.beampad.databinding.FragmentTrackpadBinding

/**
 * The precision trackpad: pointer with acceleration, tap and multi-finger
 * clicks, tap-and-drag, two-finger scroll, pinch to zoom and three-finger
 * gestures. Precision mode slows the pointer for small targets. The two
 * buttons below hold down while pressed, so they can drag too.
 */
class TrackpadFragment : SurfaceFragment() {

    override val firstTip = R.string.tip_trackpad

    private var _ui: FragmentTrackpadBinding? = null
    private val ui get() = _ui!!

    /** Air mouse (Pro): the phone's motion points while a finger holds the pad. */
    private var air: AirPointer? = null
    private var airOn = false

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
            // In air mode the finger on the pad is the clutch: pointing only while held.
            if (airOn) {
                if (active && connected) air?.start() else air?.stop()
            }
        }
        // In air mode the phone points, so finger movement on the pad does not.
        val baseMove = ui.pad.onMove
        ui.pad.onMove = { dx, dy -> if (!airOn) baseMove?.invoke(dx, dy) }

        val pointer = AirPointer(requireContext()) { dx, dy -> service(nudge = false)?.moveMouse(dx, dy) }
        air = pointer
        ui.air.isVisible = pointer.available
        airOn = pointer.available && state?.getBoolean(KEY_AIR) == true
        val airIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_hand_pointing)?.mutate()
        airIcon?.setBounds(0, 0, dp(16), dp(16))
        airIcon?.setTintList(ContextCompat.getColorStateList(requireContext(), R.color.chip_text))
        ui.air.setCompoundDrawablesRelative(airIcon, null, null, null)
        ui.air.setOnClickListener {
            val c = requireContext()
            if (!Features.isPro(c)) {
                ProActivity.open(c, Features.Pro.AIR_MOUSE)
                return@setOnClickListener
            }
            Haptics.tick(it)
            airOn = !airOn
            if (!airOn) air?.stop()
            renderAir()
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
            renderAir()
        }
    }

    /** The toggle's state, the hint, and the air mouse's speed (Settings speed and precision). */
    private fun renderAir() {
        val ui = _ui ?: return
        ui.air.isSelected = airOn
        ui.hintText.setText(
            when {
                airOn -> R.string.air_hint
                ui.pad.tapToClick -> R.string.trackpad_hint_short
                else -> R.string.trackpad_hint_no_tap
            }
        )
        air?.gain = Features.pointerSpeed(requireContext()) / Prefs.DEFAULT_POINTER_SPEED *
            (if (ui.pad.precision) TrackpadView.PRECISION_FACTOR else 1f)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AIR, airOn)
    }

    override fun onPause() {
        super.onPause()
        // Never left listening to the sensor in the background.
        air?.stop()
    }

    override fun onResume() {
        super.onResume()
        applyPadPrefs(ui.pad)
        renderAir()
    }

    override fun onConnectionChanged(connected: Boolean, changed: Boolean) {
        ui.hint.alpha = HINT_ALPHA
        if (!connected) {
            ui.padGlow.animate().alpha(0f).setDuration(160L).start()
            air?.stop()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        air?.stop()
        air = null
        _ui = null
    }

    private companion object {
        const val HINT_ALPHA = 1f
        const val KEY_AIR = "air"
    }
}
