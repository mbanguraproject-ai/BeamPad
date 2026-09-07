package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class TrackpadFragment : Fragment() {

    private var pad: TrackpadView? = null
    private val host get() = activity as? MainActivity

    private val connectionObserver: (Boolean) -> Unit = { connected ->
        pad?.alpha = if (connected) 1f else 0.4f
        pad?.isEnabled = connected
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View {
        val view = TrackpadView(requireContext()).apply {
            onMove = { dx, dy -> host?.service?.moveMouse(dx, dy) }
            onScroll = { host?.service?.scroll(it) }
            onClick = { host?.service?.click(it) }
        }
        pad = view
        return view
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        host?.observeConnection(connectionObserver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        host?.stopObserving(connectionObserver)
        pad = null
    }
}
