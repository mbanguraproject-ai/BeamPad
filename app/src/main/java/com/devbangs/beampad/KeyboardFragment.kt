package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.devbangs.beampad.databinding.FragmentKeyboardBinding

class KeyboardFragment : Fragment() {

    private var _ui: FragmentKeyboardBinding? = null
    private val ui get() = _ui!!

    private val host get() = activity as? MainActivity

    private val connectionObserver: (Boolean) -> Unit = { connected ->
        _ui?.let { view ->
            // Controls stay live-looking in both states: a greyed screen on
            // first launch reads as broken rather than as not-yet-paired.
            // The press is where the difference shows.
            view.send.isEnabled = connected
            view.dpad.connected = connected
            view.input.isEnabled = connected
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View {
        _ui = FragmentKeyboardBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        ui.send.setOnClickListener { sendInput(withEnter = false) }

        // Pressing send on the phone's own keyboard also presses Enter on the
        // TV, so a search box or password field submits. The Send button
        // types the text without Enter, for fields that should not submit.
        ui.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInput(withEnter = true)
                true
            } else false
        }

        ui.dpad.onKey = { k ->
            when (k) {
                DpadView.Key.UP -> key(HidReports.KEY_UP)
                DpadView.Key.DOWN -> key(HidReports.KEY_DOWN)
                DpadView.Key.LEFT -> key(HidReports.KEY_LEFT)
                DpadView.Key.RIGHT -> key(HidReports.KEY_RIGHT)
                DpadView.Key.OK -> key(HidReports.KEY_ENTER)
            }
        }

        // Escape rather than the consumer Back usage: Android TV honours it
        // more consistently.
        ui.back.setOnClickListener { key(HidReports.KEY_ESC) }
        ui.backspace.setOnClickListener { key(HidReports.KEY_BACKSPACE) }

        ui.home.setOnClickListener { consumer(HidReports.CC_HOME) }
        ui.menu.setOnClickListener { consumer(HidReports.CC_MENU) }
        ui.rewind.setOnClickListener { consumer(HidReports.CC_SCAN_PREV) }
        ui.playPause.setOnClickListener { consumer(HidReports.CC_PLAY_PAUSE) }
        ui.forward.setOnClickListener { consumer(HidReports.CC_SCAN_NEXT) }

        ui.volUp.setOnClickListener { consumer(HidReports.CC_VOLUME_UP) }
        ui.volDown.setOnClickListener { consumer(HidReports.CC_VOLUME_DOWN) }
        ui.mute.setOnClickListener { consumer(HidReports.CC_MUTE) }

        host?.observeConnection(connectionObserver)
    }

    private var lastNudge = 0L

    /**
     * Controls look live whether or not anything is paired, so a press with
     * no connection has to say so. Rate-limited: one nudge per few seconds,
     * not one per key.
     */
    private fun requireConnection(): HidService? {
        val service = host?.service
        if (service != null && service.isReady()) return service

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastNudge > NUDGE_INTERVAL_MS) {
            lastNudge = now
            Toast.makeText(requireContext(), R.string.not_connected, Toast.LENGTH_SHORT).show()
        }
        return null
    }

    private fun consumer(usage: Int) {
        requireConnection()?.consumerKey(usage)
    }

    private fun key(code: Byte) {
        requireConnection()?.typeKey(HidReports.MOD_NONE, code)
    }

    private fun sendInput(withEnter: Boolean) {
        val text = ui.input.text?.toString().orEmpty()
        if (text.isEmpty()) return

        val service = requireConnection() ?: return

        service.typeText(if (withEnter) text + "\n" else text) { _, skipped ->
            activity?.runOnUiThread {
                ui.input.setText("")
                if (skipped > 0) {
                    Toast.makeText(
                        requireContext(),
                        "$skipped characters could not be sent",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
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
