package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.devbangs.beampad.databinding.FragmentTrackpadBinding

class TrackpadFragment : Fragment() {

    private var _ui: FragmentTrackpadBinding? = null
    private val ui get() = _ui!!

    private val host get() = activity as? MainActivity

    private var wasConnected = false

    private var connected = false

    private val connectionObserver: (Boolean) -> Unit = { isConnected ->
        connected = isConnected
        _ui?.let { view ->
            // The pad stays live-looking either way; the glow is the cue.
            view.pad.setBackgroundResource(
                if (isConnected) R.drawable.bg_trackpad_live else R.drawable.bg_trackpad
            )
            view.hint.alpha = if (isConnected) 0.6f else 0.85f

            // One pulse on the transition only. Repeating it would turn a
            // status cue into a distraction sitting under the user's thumb.
            if (isConnected && !wasConnected) pulse(view.ring)
            wasConnected = isConnected
        }
    }

    private var lastNudge = 0L

    /** A drag that goes nowhere has to say why. One nudge per few seconds. */
    private fun nudgeIfDisconnected(): Boolean {
        if (connected) return true
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastNudge > NUDGE_INTERVAL_MS) {
            lastNudge = now
            android.widget.Toast
                .makeText(requireContext(), R.string.not_connected, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
        return false
    }

    private fun pulse(target: View) {
        target.animate().cancel()
        target.scaleX = 1f
        target.scaleY = 1f
        target.animate()
            .scaleX(1.12f).scaleY(1.12f)
            .setDuration(220)
            .withEndAction {
                target.animate().scaleX(1f).scaleY(1f).setDuration(320).start()
            }
            .start()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View {
        _ui = FragmentTrackpadBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        // Movement must not nudge: a drag fires dozens of events and would
        // queue a toast per frame. Only discrete actions report.
        ui.pad.onMove = { dx, dy -> if (connected) host?.service?.moveMouse(dx, dy) }
        ui.pad.onScroll = { if (connected) host?.service?.scroll(it) }
        ui.pad.onClick = { if (nudgeIfDisconnected()) host?.service?.click(it) }

        // The hint is guidance, not a control: it must not eat touches
        // meant for the pad underneath.
        ui.hint.isClickable = false
        ui.hint.isFocusable = false

        host?.observeConnection(connectionObserver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        host?.stopObserving(connectionObserver)
        _ui = null
    }

    private companion object {
        const val NUDGE_INTERVAL_MS = 3000L
    }
}
