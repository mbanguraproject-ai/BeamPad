package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Placeholder; built by the devices and compatibility work. */
class CompatibilityActivity : BeamActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {

        fun open(context: Context) {
            context.startActivity(Intent(context, CompatibilityActivity::class.java))
        }
    }
}
