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

    /** Tells every open screen; from billing, and from [ProPass] starting or ending. */
    fun notifyEntitlement(change: Entitlements.Change) {
        entitlementListeners.toList().forEach { it(change) }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        entitlements = Entitlements(this)
        billing = Billing(this, entitlements) { change -> notifyEntitlement(change) }
        billing.start()
        // AdGate first: the others rely on it knowing when the AdMob screen closes.
        AdGate.init(this)
        registerActivityLifecycleCallbacks(AdGate)
        registerActivityLifecycleCallbacks(AppOpenAds)
        registerActivityLifecycleCallbacks(InterstitialAds)
        ProPass.watch(this)
    }
}
