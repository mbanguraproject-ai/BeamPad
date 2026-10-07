package com.devbangs.beampad

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Consent, through Google's User Messaging Platform (UMP).
 *
 * The SDK shows the form and stores the choice (the IAB TCF string AdMob
 * reads); the app's part is the order: refresh consent on every launch,
 * show the form where it is required, never request an ad until
 * [canRequestAds], and give users a way to change or withdraw consent
 * later ([showPrivacyOptions]) wherever the regulation calls for one.
 * Which regulations apply (GDPR, US state laws) is set up as messages in
 * AdMob's Privacy & messaging page.
 */
object Consent {

    /** True once ads may legally be requested. */
    var canRequestAds: Boolean = false
        private set

    /** True once this process has an answer from the consent service. */
    var updated: Boolean = false
        private set

    private fun params() = ConsentRequestParameters.Builder()
        .setTagForUnderAgeOfConsent(false)
        .build()

    /**
     * Refreshes consent and shows the form if required, then calls
     * [onReady] once if ads may be requested. Consent given in an earlier
     * session lets ads start straight away, without waiting for the
     * network round trip, as Google recommends.
     */
    fun gather(activity: Activity, onReady: () -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        var fired = false
        fun ready() {
            if (fired) return
            fired = true
            onReady()
        }

        if (info.canRequestAds()) {
            canRequestAds = true
            ready()
        }

        info.requestConsentInfoUpdate(
            activity,
            params(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    // A form error is not fatal: canRequestAds still reflects
                    // whatever consent state the SDK settled on.
                    updated = true
                    canRequestAds = info.canRequestAds()
                    if (canRequestAds) ready()
                }
            },
            {
                // Offline or the service failed: a choice made in an earlier
                // session still applies.
                updated = true
                canRequestAds = info.canRequestAds()
                if (canRequestAds) ready()
            }
        )
    }

    /** Refreshes consent status without showing a form, for screens that need to know. */
    fun update(activity: Activity, onDone: () -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity,
            params(),
            {
                updated = true
                canRequestAds = info.canRequestAds()
                onDone()
            },
            { onDone() }
        )
    }

    /**
     * The privacy options form: where users change or withdraw consent.
     * Afterwards [canRequestAds] reflects the new choice, so a withdrawal
     * stops ad requests at once.
     */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
            canRequestAds = UserMessagingPlatform.getConsentInformation(activity).canRequestAds()
            onDone()
        }
    }

    fun privacyOptionsRequired(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity)
            .privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
}
