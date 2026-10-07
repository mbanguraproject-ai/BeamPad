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

    lateinit var prefs: Prefs
        private set

    /** Anything that needs to react to a purchase or a lapse registers here. */
    private val entitlementListeners = mutableSetOf<(Entitlements.Change) -> Unit>()

    fun observeEntitlement(listener: (Entitlements.Change) -> Unit) {
        entitlementListeners += listener
    }

    fun stopObservingEntitlement(listener: (Entitlements.Change) -> Unit) {
        entitlementListeners -= listener
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        entitlements = Entitlements(this)
        billing = Billing(this, entitlements) { change ->
            entitlementListeners.toList().forEach { it(change) }
        }
        billing.start()
        registerActivityLifecycleCallbacks(AppOpenAds)
    }
}
