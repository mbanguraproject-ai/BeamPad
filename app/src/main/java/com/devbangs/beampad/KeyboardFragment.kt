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
            listOf(
                view.send, view.up, view.down, view.left, view.right,
                view.enter, view.back, view.backspace
            ).forEach { it.isEnabled = connected }
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
        ui.send.setOnClickListener { sendInput() }

        ui.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else false
        }

        ui.up.setOnClickListener { key(HidReports.KEY_UP) }
        ui.down.setOnClickListener { key(HidReports.KEY_DOWN) }
        ui.left.setOnClickListener { key(HidReports.KEY_LEFT) }
        ui.right.setOnClickListener { key(HidReports.KEY_RIGHT) }
        ui.enter.setOnClickListener { key(HidReports.KEY_ENTER) }
        ui.back.setOnClickListener { key(HidReports.KEY_ESC) }
        ui.backspace.setOnClickListener { key(HidReports.KEY_BACKSPACE) }

        host?.observeConnection(connectionObserver)
    }

    private fun key(code: Byte) {
        val service = host?.service ?: return
        service.typeKey(HidReports.MOD_NONE, code)
    }

    private fun sendInput() {
        val text = ui.input.text?.toString().orEmpty()
        if (text.isEmpty()) return

        val service = host?.service
        if (service == null || !service.isReady()) {
            Toast.makeText(requireContext(), R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        service.typeText(text) { _, skipped ->
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
}
