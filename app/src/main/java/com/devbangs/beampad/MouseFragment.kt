package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.devbangs.beampad.databinding.FragmentMouseBinding

/**
 * Mouse mode, for pointer-centric devices. Unlike the trackpad, clicking
 * lives on dedicated buttons that hold while pressed, there is a scroll
 * wheel, and drag lock keeps the left button down for long drags (moving
 * windows, selecting text) without holding a finger on it.
 */
class MouseFragment : SurfaceFragment() {

    private var _ui: FragmentMouseBinding? = null
    private val ui get() = _ui!!

    private var dragLocked = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentMouseBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        wirePad(ui.body, ui.bodyGlow)
        bindMouseButton(ui.leftButton, HidReports.BUTTON_LEFT)
        bindMouseButton(ui.rightButton, HidReports.BUTTON_RIGHT)

        ui.wheel.onScroll = { service(nudge = false)?.scroll(it) }
        ui.wheel.onTap = { service()?.click(HidReports.BUTTON_MIDDLE) }

        bind(ui.doubleClick, Action.Click(HidReports.BUTTON_LEFT.toInt(), double = true))
        bind(ui.middleClick, Action.Click(HidReports.BUTTON_MIDDLE.toInt()))
        bind(ui.dragLock) { toggleDragLock() }
    }

    override fun onResume() {
        super.onResume()
        applyPadPrefs(ui.body)
        // The body is for pointing; clicks have their own buttons here.
        ui.body.tapToClick = app.prefs.tapToClick
        ui.wheel.speed = Features.scrollSpeed(requireContext())
        ui.wheel.reverse = Features.reverseScroll(requireContext())
    }

    private fun toggleDragLock() {
        val mouse = service()?.engine?.mouse ?: return
        dragLocked = !dragLocked
        if (dragLocked) mouse.press(HidReports.BUTTON_LEFT.toInt()) else mouse.release(HidReports.BUTTON_LEFT.toInt())
        ui.dragLock.isSelected = dragLocked
        ui.leftButton.isSelected = dragLocked
        if (dragLocked) Toast.makeText(requireContext(), R.string.drag_lock_on, Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionChanged(connected: Boolean, changed: Boolean) {
        if (!connected && dragLocked) {
            dragLocked = false
            ui.dragLock.isSelected = false
            ui.leftButton.isSelected = false
        }
    }

    override fun onPause() {
        super.onPause()
        // Never leave a button held down on the other device.
        if (dragLocked) toggleDragLock()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }
}
