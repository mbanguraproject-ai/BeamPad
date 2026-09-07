package com.devbangs.beampad

import android.content.Context

/**
 * Local record of whether ads have been removed.
 *
 * Play is the source of truth; this is a cache so the UI can decide before
 * the billing connection is up. It is only ever written from a verified
 * purchase, and it is never trusted for anything but hiding the banner.
 */
class Entitlements(context: Context) {

    private val prefs = context.getSharedPreferences("beampad_ent", Context.MODE_PRIVATE)

    var adsRemoved: Boolean
        get() = prefs.getBoolean(KEY_ADS_REMOVED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADS_REMOVED, value).apply()

    companion object {
        const val PRODUCT_REMOVE_ADS = "remove_ads"
        private const val KEY_ADS_REMOVED = "ads_removed"
    }
}
