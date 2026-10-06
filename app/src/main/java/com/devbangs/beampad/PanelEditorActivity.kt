package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Placeholder; built by the panels work. Edits the panel with [EXTRA_PANEL]. */
class PanelEditorActivity : BeamActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {
        const val EXTRA_PANEL = "panel"

        fun open(context: Context, panelId: String) {
            context.startActivity(
                Intent(context, PanelEditorActivity::class.java).putExtra(EXTRA_PANEL, panelId)
            )
        }
    }
}
