package com.devbangs.beampad

import android.app.Activity
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * GDPR consent gate.
 *
 * Ads must not be requested until consent has been gathered where it is
 * required. Outside the EEA the form usually resolves immediately with
 * nothing to show, so [onReady] fires straight away.
 */
object Consent {

    /** True once ads may legally be requested. */
    var canRequestAds: Boolean = false
        private set

    fun gather(activity: Activity, onReady: () -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)

        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    // A form error is not fatal: canRequestAds still reflects
                    // whatever consent state the SDK settled on.
                    canRequestAds = info.canRequestAds()
                    if (canRequestAds) onReady()
                }
            },
            {
                canRequestAds = false
            }
        )
    }

    /** Exposed so a settings entry can let users change their choice later. */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
    }

    fun privacyOptionsRequired(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity)
            .privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
}
