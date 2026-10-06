package com.devbangs.beampad

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/** Placeholder; built by the panels work. */
class PanelFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View = TextView(requireContext()).apply {
        text = "PanelFragment"
        gravity = Gravity.CENTER
        setTextColor(requireContext().themeColor(R.attr.bpTextDim))
    }

    companion object {
        const val ARG_PANEL = "panel"

        /** Renders the saved panel with [panelId]. */
        fun newInstance(panelId: String): PanelFragment = PanelFragment().apply {
            arguments = Bundle().apply { putString(ARG_PANEL, panelId) }
        }
    }
}
