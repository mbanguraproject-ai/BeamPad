package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Placeholder; built by the macros work. */
class MacrosActivity : BeamActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {

        fun open(context: Context) {
            context.startActivity(Intent(context, MacrosActivity::class.java))
        }
    }
}
