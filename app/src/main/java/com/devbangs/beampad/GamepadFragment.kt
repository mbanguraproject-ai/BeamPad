package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.devbangs.beampad.databinding.FragmentGamepadBinding
import kotlin.math.roundToInt

/**
 * Gamepad (Pro): two sticks, a D-pad, A B X Y, shoulder buttons, Select,
 * Home and Start, for games and emulators on a TV, a TV box or a computer.
 * Every button holds while pressed, several at once, as on a real pad.
 *
 * The gamepad is a separate part of what BeamPad presents over Bluetooth
 * and is off until the user turns it on here (see [HidReports.descriptor]),
 * because a device learns it only when pairing: turning it on restarts the
 * connection and asks for the device to be paired again. Until then this
 * surface shows that step instead of the pad. Landscape while it shows,
 * as a pad is held, except on large screens.
 */
class GamepadFragment : SurfaceFragment() {

    override val firstTip = R.string.tip_gamepad

    private var _ui: FragmentGamepadBinding? = null
    private val ui get() = _ui!!

    private val pad get() = service(nudge = false)?.engine?.gamepad

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentGamepadBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        if (resources.configuration.smallestScreenWidthDp < LARGE_SCREEN_DP) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        listOf(
            ui.padA to HidReports.PAD_A, ui.padB to HidReports.PAD_B,
            ui.padX to HidReports.PAD_X, ui.padY to HidReports.PAD_Y,
            ui.padL1 to HidReports.PAD_L1, ui.padR1 to HidReports.PAD_R1,
            ui.padL2 to HidReports.PAD_L2, ui.padR2 to HidReports.PAD_R2,
            ui.padSelect to HidReports.PAD_SELECT, ui.padStart to HidReports.PAD_START,
            ui.padHome to HidReports.PAD_HOME
        ).forEach { (button, bit) ->
            hold(button, down = { pad?.press(bit) }, up = { pad?.release(bit) })
        }

        listOf(
            ui.padUp to HidReports.HAT_UP, ui.padRight to HidReports.HAT_RIGHT,
            ui.padDown to HidReports.HAT_DOWN, ui.padLeft to HidReports.HAT_LEFT
        ).forEach { (button, direction) ->
            hold(button, down = { pad?.hat(direction) }, up = { pad?.hat(HidReports.HAT_CENTER) })
        }

        ui.leftStick.onMove = { x, y -> pad?.stick(right = false, x = axis(x), y = axis(y)) }
        ui.rightStick.onMove = { x, y -> pad?.stick(right = true, x = axis(x), y = axis(y)) }

        ui.setupAction.setOnClickListener {
            Haptics.tick(it)
            turnOn()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        // Never leave a button held or a stick pushed on the other device.
        pad?.reset()
    }

    override fun onConnectionChanged(connected: Boolean, changed: Boolean) {
        render()
    }

    /** The pad once gamepad support is on; the step that turns it on before. */
    private fun render() {
        val ui = _ui ?: return
        val on = app.prefs.gamepad
        ui.pad.isVisible = on
        ui.setup.isVisible = !on
    }

    /**
     * Turns gamepad support on and restarts the connection so it is
     * registered, then says what the device needs: a new pairing.
     */
    private fun turnOn() {
        val context = context ?: return
        app.prefs.gamepad = true
        host?.service?.restart()
        render()
        Sheets.confirm(
            context,
            getString(R.string.pad_repair_title),
            getString(R.string.pad_repair_body),
            getString(R.string.got_it)
        ) { }
    }

    private fun axis(value: Float): Int = (value * 127f).roundToInt().coerceIn(-127, 127)

    /**
     * A button that holds while pressed: down on touch, up on lift or when
     * the finger slides off. Accessibility services click instead, which
     * presses and releases at once.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun hold(view: View, down: () -> Unit, up: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    Haptics.tick(v)
                    // Nudges once when nothing is connected.
                    if (service() != null) down()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (v.isPressed) up()
                    v.isPressed = false
                }
            }
            true
        }
        view.setOnClickListener {
            down()
            it.postDelayed({ up() }, ACCESSIBILITY_PRESS_MS)
        }
    }

    override fun onDestroyView() {
        pad?.reset()
        // Leaving the gamepad gives the phone its own orientation back; a
        // rotation (the activity being recreated) keeps landscape.
        activity?.takeUnless { it.isChangingConfigurations }
            ?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        super.onDestroyView()
        _ui = null
    }

    private companion object {
        const val LARGE_SCREEN_DP = 600
        const val ACCESSIBILITY_PRESS_MS = 80L
    }
}
