package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.devbangs.beampad.databinding.FragmentGamepadBinding
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Gamepad (Pro): two sticks, a D-pad, A B X Y, shoulder buttons, analog
 * triggers, stick clicks, Select, Home and Start, for games and emulators
 * on a TV, a TV box or a computer. Every button holds while pressed,
 * several at once, as on a real pad.
 *
 * - The D-pad is one surface under the thumb, with diagonals, and rolls
 *   from one direction to the next ([GamepadDpadView]).
 * - The triggers press as far as the finger is high ([TriggerView]), and
 *   count as pressed buttons too for games that only read those.
 * - A double tap clicks a stick; L3 and R3 are also on the top row.
 * - Floating sticks centre where the thumb lands (Settings).
 * - Tilt steering turns the phone into a wheel for the left stick.
 * - The buttons can be named as on an Xbox, PlayStation or Nintendo pad;
 *   the device sees the same buttons whichever names show.
 *
 * Full screen while it shows: only the pad and its close button, which
 * asks first, as Back does. The status card stays while nothing is
 * connected, as the way to connect.
 *
 * The gamepad is a separate part of what BeamPad presents over Bluetooth
 * and is off until the user turns it on here (see [HidReports.descriptor]),
 * because a device learns it only when pairing: turning it on restarts the
 * connection and asks for the device to be paired again. Until then this
 * surface shows that step instead of the pad. Landscape while it shows,
 * as a pad is held, except on large screens.
 */
class GamepadFragment : SurfaceFragment(), SensorEventListener {

    override val firstTip = R.string.tip_gamepad

    // Full screen, so the top bar named in the usual hint is not there.
    override val notConnectedHint = R.string.pad_not_connected

    /** Back while playing asks first, like the close button. */
    private val askBeforeLeaving = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = askClose()
    }

    private var _ui: FragmentGamepadBinding? = null
    private val ui get() = _ui!!

    private val pad get() = service(nudge = false)?.engine?.gamepad

    private val sensors by lazy { requireContext().getSystemService(SensorManager::class.java) }
    private var tiltSensor: Sensor? = null

    /** The steering angle as a share of full lock, smoothed. */
    private var steer = 0f
    private var sentSteer = 0

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
            ui.padL3 to HidReports.PAD_L3, ui.padR3 to HidReports.PAD_R3,
            ui.padSelect to HidReports.PAD_SELECT, ui.padStart to HidReports.PAD_START,
            ui.padHome to HidReports.PAD_HOME
        ).forEach { (button, bit) ->
            hold(button, down = { pad?.press(bit) }, up = { pad?.release(bit) })
        }

        ui.dpad.onChange = { dpad ->
            // Nudges once when nothing is connected.
            if (dpad != 0) service()
            pad?.hat(HidReports.hat(dpad))
        }

        ui.padL2.onChange = { value -> trigger(right = false, value) }
        ui.padR2.onChange = { value -> trigger(right = true, value) }

        ui.leftStick.onMove = { x, y ->
            // With tilt steering on, a thumb on the stick takes over until
            // it lifts; then the tilt steers again.
            if (tilting() && !ui.leftStick.isActive) sendSteer(force = true)
            else pad?.stick(right = false, x = axis(x), y = axis(y))
        }
        ui.rightStick.onMove = { x, y -> pad?.stick(right = true, x = axis(x), y = axis(y)) }
        ui.leftStick.onClick = { down -> click(HidReports.PAD_L3, down) }
        ui.rightStick.onClick = { down -> click(HidReports.PAD_R3, down) }

        TooltipCompat.setTooltipText(ui.padTilt, getString(R.string.pad_tilt))
        ui.padTilt.setOnClickListener {
            Haptics.tick(it)
            toggleTilt()
        }

        ui.setupAction.setOnClickListener {
            Haptics.tick(it)
            turnOn()
        }

        ui.padClose.setOnClickListener {
            Haptics.tick(it)
            askClose()
        }
        TooltipCompat.setTooltipText(ui.padClose, getString(R.string.pad_close))
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, askBeforeLeaving)
    }

    override fun onResume() {
        super.onResume()
        // Settings can change these while the gamepad is in the back stack.
        applyLabels()
        ui.leftStick.floating = app.prefs.padFloating
        ui.rightStick.floating = app.prefs.padFloating
        render()
    }

    override fun onPause() {
        super.onPause()
        stopTilt()
        // Never leave a button held or a stick pushed on the other device.
        pad?.reset()
    }

    override fun onConnectionChanged(connected: Boolean, changed: Boolean) {
        render()
    }

    /**
     * The pad, full screen, once gamepad support is on; the step that turns
     * it on before, with the app around it as usual.
     */
    private fun render() {
        val ui = _ui ?: return
        val on = app.prefs.gamepad
        ui.pad.isVisible = on
        ui.setupScroll.isVisible = !on
        ui.padTilt.isSelected = app.prefs.padTilt
        askBeforeLeaving.isEnabled = on
        if (isResumed) (activity as? MainActivity)?.setImmersive(on)
        if (on && app.prefs.padTilt && isResumed) startTilt() else stopTilt()
    }

    /** "Close the gamepad?": back to the controls the user came from. */
    private fun askClose() {
        val context = context ?: return
        Sheets.confirm(
            context,
            getString(R.string.pad_close_title),
            getString(R.string.pad_close_body),
            getString(R.string.pad_close_action)
        ) {
            pad?.reset()
            (parentFragment as? ControlFragment)?.closeGamepad()
        }
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

    // ---- Triggers and stick clicks -----------------------------------------

    /** A trigger at 0..1: its analog value, and its button while it is touched. */
    private fun trigger(right: Boolean, value: Float) {
        val bit = if (right) HidReports.PAD_R2 else HidReports.PAD_L2
        if (value > 0f) {
            if (service() == null) return
            pad?.press(bit)
        } else {
            pad?.release(bit)
        }
        pad?.trigger(right, (value * 255f).roundToInt())
    }

    private fun click(bit: Int, down: Boolean) {
        if (down) {
            if (service() != null) pad?.press(bit)
        } else {
            pad?.release(bit)
        }
    }

    // ---- Tilt steering -------------------------------------------------------

    private fun tilting(): Boolean = tiltSensor != null

    private fun toggleTilt() {
        val on = !app.prefs.padTilt
        if (on && tiltSensorOrNull() == null) {
            Toast.makeText(requireContext(), R.string.pad_tilt_none, Toast.LENGTH_SHORT).show()
            return
        }
        app.prefs.padTilt = on
        render()
        if (!on && !ui.leftStick.isActive) pad?.stick(right = false, x = 0, y = 0)
        Toast.makeText(requireContext(), if (on) R.string.pad_tilt_on else R.string.pad_tilt_off, Toast.LENGTH_SHORT).show()
    }

    /** The gravity sensor where there is one: smooth already. The accelerometer otherwise. */
    private fun tiltSensorOrNull(): Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private fun startTilt() {
        if (tiltSensor != null) return
        val sensor = tiltSensorOrNull() ?: return
        tiltSensor = sensor
        steer = 0f
        sentSteer = Int.MIN_VALUE
        sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopTilt() {
        if (tiltSensor == null) return
        sensors?.unregisterListener(this)
        tiltSensor = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * The phone held sideways is the wheel: turning it clockwise steers
     * right. Gravity's direction in the screen's plane gives the angle,
     * whichever way round the phone is held and however far it leans back.
     */
    override fun onSensorChanged(event: SensorEvent) {
        val g = event.values
        val (sx, sy) = when (_ui?.root?.display?.rotation) {
            Surface.ROTATION_90 -> -g[1] to g[0]
            Surface.ROTATION_180 -> -g[0] to -g[1]
            Surface.ROTATION_270 -> g[1] to -g[0]
            else -> g[0] to g[1]
        }
        // Lying flat there is no wheel to turn: steer straight.
        val target = if (hypot(sx, sy) < MIN_TILT_GRAVITY) 0f else {
            val degrees = Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
            val beyondDead = (abs(degrees) - TILT_DEAD_DEGREES).coerceAtLeast(0f)
            (sign(degrees) * beyondDead / (TILT_FULL_DEGREES - TILT_DEAD_DEGREES)).coerceIn(-1f, 1f)
        }
        steer += (target - steer) * TILT_SMOOTHING
        sendSteer(force = false)
    }

    /** The tilt to the left stick, unless a thumb is on it. Only when it changed, unless [force]. */
    private fun sendSteer(force: Boolean) {
        val ui = _ui ?: return
        if (!tilting() || ui.leftStick.isActive) return
        val x = axis(steer)
        if (!force && x == sentSteer) return
        sentSteer = x
        pad?.stick(right = false, x = x, y = 0)
    }

    // ---- Labels --------------------------------------------------------------

    /** What a face button shows: a letter in a colour, or a symbol. */
    private class Face(val text: String?, @DrawableRes val icon: Int?, @ColorRes val color: Int?, val description: String)

    /** Names every button as on the chosen controller. The bits stay the same. */
    private fun applyLabels() {
        val ui = _ui ?: return
        when (app.prefs.padLabels) {
            Prefs.PadLabels.XBOX -> {
                shoulders("LB", "RB", "LT", "RT", "LS", "RS")
                ui.padSelect.text = getString(R.string.pad_view)
                ui.padStart.text = getString(R.string.pad_menu)
                faces(
                    bottom = Face("A", null, R.color.pad_green, "A"),
                    right = Face("B", null, R.color.pad_red, "B"),
                    left = Face("X", null, R.color.pad_blue, "X"),
                    top = Face("Y", null, R.color.pad_yellow, "Y")
                )
            }
            Prefs.PadLabels.PLAYSTATION -> {
                shoulders("L1", "R1", "L2", "R2", "L3", "R3")
                ui.padSelect.text = getString(R.string.pad_create)
                ui.padStart.text = getString(R.string.pad_options)
                faces(
                    bottom = Face(null, R.drawable.ic_pad_cross, R.color.pad_blue, getString(R.string.pad_cross)),
                    right = Face(null, R.drawable.ic_pad_circle, R.color.pad_red, getString(R.string.pad_circle)),
                    left = Face(null, R.drawable.ic_pad_square, R.color.pad_pink, getString(R.string.pad_square)),
                    top = Face(null, R.drawable.ic_pad_triangle, R.color.pad_teal, getString(R.string.pad_triangle))
                )
            }
            Prefs.PadLabels.NINTENDO -> {
                shoulders("L", "R", "ZL", "ZR", "LS", "RS")
                ui.padSelect.text = "−"
                ui.padStart.text = "+"
                ui.padSelect.contentDescription = getString(R.string.pad_select)
                ui.padStart.contentDescription = getString(R.string.pad_start)
                faces(
                    bottom = Face("B", null, null, "B"),
                    right = Face("A", null, null, "A"),
                    left = Face("Y", null, null, "Y"),
                    top = Face("X", null, null, "X")
                )
            }
        }
    }

    private fun shoulders(l1: String, r1: String, l2: String, r2: String, l3: String, r3: String) {
        ui.padL1.text = l1
        ui.padR1.text = r1
        ui.padL2.label = l2
        ui.padR2.label = r2
        ui.padL3.text = l3
        ui.padR3.text = r3
        // Spoken as written unless set below (Nintendo's - and +).
        ui.padSelect.contentDescription = null
        ui.padStart.contentDescription = null
    }

    private fun faces(bottom: Face, right: Face, left: Face, top: Face) {
        listOf(ui.padA to bottom, ui.padB to right, ui.padX to left, ui.padY to top).forEach { (button, face) ->
            face(button, face)
        }
    }

    private fun face(button: MaterialButton, face: Face) {
        val context = button.context
        val color = face.color?.let { ContextCompat.getColor(context, it) } ?: context.themeColor(R.attr.bpText)
        button.text = face.text ?: ""
        button.setTextColor(color)
        button.icon = face.icon?.let { ContextCompat.getDrawable(context, it) }
        button.iconTint = ColorStateList.valueOf(color)
        button.contentDescription = face.description
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
        stopTilt()
        pad?.reset()
        (activity as? MainActivity)?.setImmersive(false)
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

        /** Turning the phone this far is full lock. */
        const val TILT_FULL_DEGREES = 35f

        /** Below this the wheel reads as straight, so a steady hand is steady. */
        const val TILT_DEAD_DEGREES = 2.5f

        /** Gravity in the screen's plane below this (m/s²): the phone is lying flat. */
        const val MIN_TILT_GRAVITY = 2.5f

        /** Each reading moves the steering this share of the way: smooth, still quick. */
        const val TILT_SMOOTHING = 0.35f
    }
}
