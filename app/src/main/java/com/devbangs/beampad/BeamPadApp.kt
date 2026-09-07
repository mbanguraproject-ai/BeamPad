package com.devbangs.beampad

import android.app.Application

/**
 * Owns the single billing connection.
 *
 * One client for the whole process: two Activities each opening their own
 * connection duplicates the Play service binding, and whichever finishes
 * last decides what the other one saw.
 */
class BeamPadApp : Application() {

    lateinit var entitlements: Entitlements
        private set

    lateinit var billing: Billing
        private set

    /** Anything that needs to react when ads are removed registers here. */
    private val entitlementListeners = mutableSetOf<() -> Unit>()

    fun observeEntitlement(listener: () -> Unit) {
        entitlementListeners += listener
    }

    fun stopObservingEntitlement(listener: () -> Unit) {
        entitlementListeners -= listener
    }

    override fun onCreate() {
        super.onCreate()
        entitlements = Entitlements(this)
        billing = Billing(this, entitlements) {
            entitlementListeners.toList().forEach { it() }
        }
        billing.start()
    }
}
