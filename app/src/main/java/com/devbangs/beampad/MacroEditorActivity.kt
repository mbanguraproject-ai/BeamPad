package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Placeholder; built by the macros work. Edits [EXTRA_MACRO], or a new macro when absent. */
class MacroEditorActivity : BeamActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {
        const val EXTRA_MACRO = "macro"

        fun open(context: Context, macroId: String?) {
            context.startActivity(
                Intent(context, MacroEditorActivity::class.java).putExtra(EXTRA_MACRO, macroId)
            )
        }
    }
}
