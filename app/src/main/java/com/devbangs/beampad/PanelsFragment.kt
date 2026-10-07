package com.devbangs.beampad

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.devbangs.beampad.databinding.FragmentPageBinding

/**
 * Panels: the user's own control surfaces and the macros they run, with
 * templates and examples to start from. The blueprint's "platform"
 * feature; Pro, with the lists visible to everyone so the value is clear
 * before the paywall.
 */
class PanelsFragment : Fragment(), Library.Host {

    private var _ui: FragmentPageBinding? = null
    private val ui get() = _ui!!

    private var showingMacros = false

    override val hostActivity: Activity get() = requireActivity()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentPageBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        showingMacros = state?.getBoolean(KEY_MACROS) ?: false
        Ui.header(ui.header.root, getString(R.string.tab_panels), getString(R.string.panels_subtitle))
        Ui.headerButton(ui.header.root, getString(R.string.add_new), R.drawable.ic_plus) {
            if (showingMacros) Library.newMacro(this) else Library.newPanel(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MACROS, showingMacros)
    }

    override fun refresh() {
        val ui = _ui ?: return
        val content = ui.content
        content.removeAllViews()

        val chips = android.widget.LinearLayout(requireContext()).apply {
            setPadding(Ui.dp(context, 16), Ui.dp(context, 4), Ui.dp(context, 16), 0)
        }
        content.addView(chips)
        Ui.chipGroup(
            chips,
            listOf(false to getString(R.string.panels_tab_panels), true to getString(R.string.panels_tab_macros)),
            showingMacros
        ) { macros ->
            showingMacros = macros
            refresh()
        }

        if (showingMacros) Library.renderMacros(content, this) else Library.renderPanels(content, this)
    }

    override fun openPanel(id: String) {
        // Switches to the Control tab when it is not the one showing.
        (activity as? MainActivity)?.openControl(panelId = id)
    }

    override fun runMacro(macro: Macro) {
        MacroRunSheet.run(requireActivity(), (activity as? MainActivity)?.service, macro)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }

    private companion object {
        const val KEY_MACROS = "macros"
    }
}
