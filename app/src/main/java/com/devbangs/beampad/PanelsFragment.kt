package com.devbangs.beampad

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/** Placeholder; built by the panels work. */
class PanelsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View = TextView(requireContext()).apply {
        text = "PanelsFragment"
        gravity = Gravity.CENTER
        setTextColor(requireContext().themeColor(R.attr.bpTextDim))
    }
}
