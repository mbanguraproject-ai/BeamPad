package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton

/**
 * A custom panel on the Control tab: the user's own surface, rendered by
 * the same grid as the editor. An Edit button sits at the bottom, so
 * changing a panel is one tap from using it.
 */
class PanelFragment : SurfaceFragment(), PanelGrid.Host {

    private var grid: PanelGrid? = null
    private var empty: View? = null

    private val panelId: String get() = requireArguments().getString(ARG_PANEL).orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val frame = FrameLayout(context)
        val grid = PanelGrid(context).apply {
            host = this@PanelFragment
            val side = resources.getDimensionPixelSize(R.dimen.gutter) - Ui.dp(context, 5)
            setPadding(side, 0, side, Ui.dp(context, 8))
        }
        frame.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        val empty = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        Ui.empty(empty, R.drawable.ic_layout, getString(R.string.panel_empty_title), getString(R.string.panel_empty_body),
            getString(R.string.panel_edit)) { PanelEditorActivity.open(context, panelId) }
        frame.addView(empty, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        column.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val edit = layoutInflater.inflate(R.layout.ui_button_small, column, false) as MaterialButton
        edit.text = getString(R.string.panel_edit)
        edit.setIconResource(R.drawable.ic_pencil_simple)
        edit.setOnClickListener { PanelEditorActivity.open(context, panelId) }
        column.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = Ui.dp(context, 10)
        })

        this.grid = grid
        this.empty = empty
        return column
    }

    override fun onResume() {
        super.onResume()
        // The editor may have changed the panel meanwhile.
        reload()
    }

    private fun reload() {
        val panel = PanelStore(requireContext()).get(panelId)
        val components = panel?.components.orEmpty()
        grid?.submit(components)
        empty?.isVisible = components.isEmpty()
    }

    override fun onConnectionChanged(connected: Boolean, changed: Boolean) {
        if (changed) reload()
    }

    // PanelGrid.Host
    override fun sendAction(action: Action) = perform(action)
    override fun liveService(nudge: Boolean): HidService? = service(nudge)
    override fun bindPad(pad: TrackpadView) {
        wirePad(pad)
        applyPadPrefs(pad)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        grid = null
        empty = null
    }

    companion object {
        const val ARG_PANEL = "panel"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Renders the saved panel with [panelId]. */
        fun newInstance(panelId: String) = PanelFragment().apply {
            arguments = Bundle().apply { putString(ARG_PANEL, panelId) }
        }
    }
}
