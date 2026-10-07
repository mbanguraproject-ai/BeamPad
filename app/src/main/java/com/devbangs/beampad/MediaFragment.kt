package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.devbangs.beampad.databinding.FragmentMediaBinding

/**
 * Media control: transport, seek (hold to keep scanning), volume as a
 * drag slider, and the player extras the blueprint marks "where
 * supported": subtitles, fullscreen and playback speed.
 */
class MediaFragment : SurfaceFragment() {

    private var _ui: FragmentMediaBinding? = null
    private val ui get() = _ui!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentMediaBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        bind(ui.playPause, Action.Consumer(HidReports.CC_PLAY_PAUSE))
        bind(ui.prev, Action.Consumer(HidReports.CC_SCAN_PREV))
        bind(ui.next, Action.Consumer(HidReports.CC_SCAN_NEXT))
        bindRepeating(ui.rewind, Action.Consumer(HidReports.CC_REWIND))
        bindRepeating(ui.forward, Action.Consumer(HidReports.CC_FAST_FORWARD))
        bind(ui.stop, Action.Consumer(HidReports.CC_STOP))
        bind(ui.mute, Action.Consumer(HidReports.CC_MUTE))

        ui.volume.onStep = { direction ->
            consumer(if (direction > 0) HidReports.CC_VOLUME_UP else HidReports.CC_VOLUME_DOWN)
        }

        listOf(
            ui.subtitles to "subtitles",
            ui.fullscreen to "fullscreen",
            ui.slower to "speed_down",
            ui.faster to "speed_up"
        ).forEach { (button, id) ->
            Actions.byId(id)?.let { bind(button, it.action) }
        }
    }

    override fun onVolumeKey(up: Boolean): Boolean {
        if (!connected) return false
        consumer(if (up) HidReports.CC_VOLUME_UP else HidReports.CC_VOLUME_DOWN)
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }
}
