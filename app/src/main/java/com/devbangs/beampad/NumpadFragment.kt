package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.devbangs.beampad.databinding.FragmentNumpadBinding

/**
 * Number pad: the keypad of a full keyboard, for spreadsheets, calculators
 * and number entry. Sends the keypad's own keys, not the number row, so
 * the computer treats them exactly as a real numpad: with Num Lock off they
 * move the cursor, as they would there. Backspace repeats while held.
 */
class NumpadFragment : SurfaceFragment() {

    override val firstTip = R.string.tip_numpad

    private var _ui: FragmentNumpadBinding? = null
    private val ui get() = _ui!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentNumpadBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        listOf(
            ui.kp0 to 0, ui.kp1 to 1, ui.kp2 to 2, ui.kp3 to 3, ui.kp4 to 4,
            ui.kp5 to 5, ui.kp6 to 6, ui.kp7 to 7, ui.kp8 to 8, ui.kp9 to 9
        ).forEach { (button, digit) ->
            bind(button, Action.Key(HidReports.keypadDigit(digit).toInt()))
        }
        bind(ui.numLock, Action.Key(HidReports.KP_NUM_LOCK.toInt()))
        bind(ui.kpDivide, Action.Key(HidReports.KP_DIVIDE.toInt()))
        bind(ui.kpMultiply, Action.Key(HidReports.KP_MULTIPLY.toInt()))
        bind(ui.kpMinus, Action.Key(HidReports.KP_MINUS.toInt()))
        bind(ui.kpPlus, Action.Key(HidReports.KP_PLUS.toInt()))
        bind(ui.kpEnter, Action.Key(HidReports.KP_ENTER.toInt()))
        bind(ui.kpDot, Action.Key(HidReports.KP_DOT.toInt()))
        bind(ui.kpTab, Action.Key(HidReports.KEY_TAB.toInt()))
        bindRepeating(ui.kpBackspace, Action.Key(HidReports.KEY_BACKSPACE.toInt()))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }
}
