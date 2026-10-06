package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Placeholder; built by the devices work. Name, icon, labels and profile of one device. */
class DeviceActivity : BeamActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {
        const val EXTRA_ADDRESS = "address"

        fun open(context: Context, address: String) {
            context.startActivity(
                Intent(context, DeviceActivity::class.java).putExtra(EXTRA_ADDRESS, address)
            )
        }
    }
}
