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

    private val connectionObserver: (Boolean) -> Unit = { connected ->
        _ui?.let { view ->
            view.pad.isEnabled = connected
            view.pad.setBackgroundResource(
                if (connected) R.drawable.bg_trackpad_live else R.drawable.bg_trackpad
            )
            view.hint.alpha = if (connected) 0.75f else 0.5f

            // One pulse on the transition only. Repeating it would turn a
            // status cue into a distraction sitting under the user's thumb.
            if (connected && !wasConnected) pulse(view.ring)
            wasConnected = connected
        }
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
        ui.pad.onMove = { dx, dy -> host?.service?.moveMouse(dx, dy) }
        ui.pad.onScroll = { host?.service?.scroll(it) }
        ui.pad.onClick = { host?.service?.click(it) }

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
}
