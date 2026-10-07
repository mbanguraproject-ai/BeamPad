package com.devbangs.beampad

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlin.math.roundToInt

/**
 * Base for every control surface (keyboard, trackpad, remote, media,
 * mouse, presentation, custom panels). Owns the things they must all do
 * the same way: knowing whether a device is connected, sending through the
 * one input engine, saying so once when a press goes nowhere, haptics, and
 * press-and-hold repeat.
 *
 * Controls look live whether or not anything is paired: a greyed screen on
 * first launch reads as broken rather than as not-yet-paired. The press is
 * where the difference shows, as a nudge to connect.
 */
abstract class SurfaceFragment : Fragment() {

    protected val host: MainActivity? get() = activity as? MainActivity
    protected val app: BeamPadApp get() = requireActivity().application as BeamPadApp

    /** Whether a device is connected and ready for input. */
    protected var connected = false
        private set

    private var lastNudge = 0L

    private val connectionObserver: (Boolean) -> Unit = { now ->
        val changed = now != connected
        connected = now
        if (view != null) onConnectionChanged(now, changed)
    }

    /**
     * The phone's volume button was pressed while this surface showed.
     * Return true to consume it (the phone's own volume then stays put).
     */
    open fun onVolumeKey(up: Boolean): Boolean = false

    /** Called on start and on every change. [changed] is false for the first report. */
    protected open fun onConnectionChanged(connected: Boolean, changed: Boolean) = Unit

    /**
     * One line taught the first time this surface opens (the blueprint's
     * contextual teaching instead of a tour): gestures, holds, the volume
     * buttons. Shown once per surface, ever.
     */
    protected open val firstTip: Int? = null

    override fun onResume() {
        super.onResume()
        showFirstTip()
    }

    private fun showFirstTip() {
        val tip = firstTip ?: return
        val view = view ?: return
        val store = requireContext().getSharedPreferences(TIPS_FILE, android.content.Context.MODE_PRIVATE)
        val key = javaClass.simpleName
        if (store.getBoolean(key, false)) return
        store.edit().putBoolean(key, true).apply()
        Snackbar.make(view, tip, Snackbar.LENGTH_LONG)
            .setDuration(TIP_DURATION_MS)
            .setAction(R.string.got_it) { }
            .show()
    }

    override fun onStart() {
        super.onStart()
        host?.observeConnection(connectionObserver)
    }

    override fun onStop() {
        super.onStop()
        host?.stopObserving(connectionObserver)
    }

    /**
     * The live service, or null after a rate-limited nudge. Continuous
     * input (pointer movement, scrolling) passes [nudge] false: a drag fires
     * dozens of events and must not queue a toast per frame.
     */
    protected fun service(nudge: Boolean = true): HidService? {
        val service = host?.service
        if (service != null && service.isReady()) return service
        if (nudge) {
            val now = SystemClock.uptimeMillis()
            if (now - lastNudge > NUDGE_INTERVAL_MS) {
                lastNudge = now
                context?.let { Toast.makeText(it, R.string.not_connected_hint, Toast.LENGTH_SHORT).show() }
            }
        }
        return null
    }

    protected fun perform(action: Action) {
        val service = service() ?: return
        if (action is Action.RunMacro) {
            val macro = MacroStore(requireContext()).get(action.macroId) ?: return
            MacroRunSheet.run(requireActivity(), service, macro)
            return
        }
        service.engine.perform(action)
    }

    protected fun key(usage: Number, modifiers: Number = 0) =
        perform(Action.Key(usage.toInt() and 0xFF, modifiers.toInt() and 0xFF))

    protected fun consumer(usage: Int) = perform(Action.Consumer(usage))

    /** One tap, one action, with the light haptic tick. */
    protected fun bind(view: View, action: Action) {
        view.setOnClickListener {
            Haptics.tick(it)
            perform(action)
        }
    }

    protected fun bind(view: View, block: () -> Unit) {
        view.setOnClickListener {
            Haptics.tick(it)
            block()
        }
    }

    /**
     * Fires on touch-down and repeats while held: volume, channel, seek and
     * arrow keys behave like the hardware buttons they replace.
     */
    @SuppressLint("ClickableViewAccessibility")
    protected fun bindRepeating(view: View, action: Action) {
        val repeat = object : Runnable {
            override fun run() {
                if (!view.isPressed) return
                perform(action)
                view.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    Haptics.tick(v)
                    perform(action)
                    v.postDelayed(repeat, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val inside = event.x >= 0 && event.y >= 0 && event.x <= v.width && event.y <= v.height
                    if (!inside && v.isPressed) {
                        v.isPressed = false
                        v.removeCallbacks(repeat)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(repeat)
                }
            }
            true
        }
        // Accessibility services click rather than touch; a finger never
        // reaches this listener because the touch listener consumes it.
        view.setOnClickListener { perform(action) }
    }

    protected fun dp(value: Number): Int = Ui.dp(requireContext(), value)

    /**
     * Applies Button size and Large controls to every fixed-height key under
     * [root]; the heights in the layouts are the Regular size. Safe to call
     * on every resume (Settings may have changed): the size always comes
     * from the layout's value, kept in a tag, never from the current height.
     */
    protected fun scaleKeys(root: View) {
        val scale = ControlSizing.scale(root.context)
        fun walk(view: View) {
            if (view is MaterialButton) {
                val params = view.layoutParams
                val base = view.getTag(R.id.base_height) as? Int
                    ?: params.height.also { view.setTag(R.id.base_height, it) }
                val target = (base * scale).roundToInt()
                if (base > 0 && params.height != target) {
                    params.height = target
                    view.layoutParams = params
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
    }

    /**
     * Connects a pad to the engine. Movement and scrolling are continuous
     * and never nudge; clicks, zoom steps and gestures are discrete and do.
     * [glow] fades in under the finger while connected.
     */
    protected fun wirePad(pad: TrackpadView, glow: View? = null) {
        pad.onMove = { dx, dy -> service(nudge = false)?.moveMouse(dx, dy) }
        pad.onScroll = { service(nudge = false)?.scroll(it) }
        pad.onClick = { button -> service()?.click(button) }
        pad.onDrag = { down ->
            service(nudge = false)?.engine?.mouse?.let { mouse ->
                if (down) mouse.press(HidReports.BUTTON_LEFT.toInt()) else mouse.release(HidReports.BUTTON_LEFT.toInt())
            }
        }
        pad.onZoom = { step ->
            Actions.byId(if (step > 0) "zoom_in" else "zoom_out")?.let { perform(it.action) }
        }
        pad.onSwipe = { direction ->
            context?.let { c ->
                Features.gesture(c, direction)?.let { id -> Actions.byId(id)?.let { perform(it.action) } }
            }
        }
        if (glow != null) {
            pad.onTouchActive = { active ->
                glow.animate()
                    .alpha(if (active && connected) 1f else 0f)
                    .setDuration(if (active) 90L else 260L)
                    .start()
            }
        }
    }

    /**
     * A physical-feeling mouse button: down on touch, up on lift, so a
     * quick tap is a click and holding it while moving the pointer with
     * another finger drags.
     */
    @SuppressLint("ClickableViewAccessibility")
    protected fun bindMouseButton(view: View, button: Byte) {
        val bit = button.toInt()
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    Haptics.tick(v)
                    service()?.engine?.mouse?.press(bit)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    service(nudge = false)?.engine?.mouse?.release(bit)
                }
            }
            true
        }
        view.setOnClickListener { service()?.click(button) }
    }

    /** Reads pointer settings on every resume: they change in Settings and revert if Pro lapses. */
    protected fun applyPadPrefs(pad: TrackpadView) {
        val c = requireContext()
        val prefs = app.prefs
        pad.sensitivity = Features.pointerSpeed(c)
        pad.acceleration = Features.acceleration(c)
        pad.scrollSpeed = Features.scrollSpeed(c)
        pad.reverseScroll = Features.reverseScroll(c)
        pad.pinchToZoom = Features.pinchToZoom(c)
        pad.gesturesEnabled = Features.isPro(c)
        pad.tapToClick = prefs.tapToClick
        pad.touchResponse = prefs.touchResponse
    }

    companion object {
        const val TIPS_FILE = "beampad_tips"
        const val TIP_DURATION_MS = 7000

        const val NUDGE_INTERVAL_MS = 3000L
        const val REPEAT_DELAY_MS = 420L
        const val REPEAT_INTERVAL_MS = 110L
    }
}
