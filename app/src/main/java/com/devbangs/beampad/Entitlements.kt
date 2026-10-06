package com.devbangs.beampad

import android.content.Context

/**
 * Local record of what the user has paid for.
 *
 * Play is the source of truth; this is a cache so the UI can decide before
 * the billing connection is up. It is only ever written from a verified
 * purchase or from a successful query of Play.
 *
 * One-time purchases (remove ads, Pro lifetime) are never revoked: a paying
 * user must not lose them because the connection was slow or the device
 * offline. A Pro subscription is revoked only when Play answers successfully
 * and no longer lists it, which is how an expired subscription shows up.
 */
class Entitlements(context: Context) {

    private val prefs = context.getSharedPreferences("beampad_ent", Context.MODE_PRIVATE)

    /** The original one-time remove-ads purchase. Key kept from v1. */
    var removeAdsPurchased: Boolean
        get() = prefs.getBoolean(KEY_ADS_REMOVED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADS_REMOVED, value).apply()

    var proLifetime: Boolean
        get() = prefs.getBoolean(KEY_PRO_LIFETIME, false)
        set(value) = prefs.edit().putBoolean(KEY_PRO_LIFETIME, value).apply()

    var proSubscription: Boolean
        get() = prefs.getBoolean(KEY_PRO_SUB, false)
        set(value) = prefs.edit().putBoolean(KEY_PRO_SUB, value).apply()

    val isPro: Boolean
        get() = proLifetime || proSubscription

    /**
     * What the ad code reads. Pro includes ad removal, so it switches the
     * banner off through the same flag the one-time purchase always used.
     */
    val adsRemoved: Boolean
        get() = removeAdsPurchased || isPro

    enum class Change { ADS_REMOVED, PRO_STARTED, PRO_ENDED }

    companion object {
        const val PRODUCT_REMOVE_ADS = "remove_ads"

        /** Subscription with monthly and yearly base plans. */
        const val PRODUCT_PRO_SUB = "beampad_pro"

        /** One-time purchase that unlocks Pro for good. Optional in Play. */
        const val PRODUCT_PRO_LIFETIME = "beampad_pro_lifetime"

        private const val KEY_ADS_REMOVED = "ads_removed"
        private const val KEY_PRO_LIFETIME = "pro_lifetime"
        private const val KEY_PRO_SUB = "pro_sub"
    }
}
