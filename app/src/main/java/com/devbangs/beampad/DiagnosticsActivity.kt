package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Placeholder; built by the settings and diagnostics work. */
class DiagnosticsActivity : BeamActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {

        fun open(context: Context) {
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
    }
}
