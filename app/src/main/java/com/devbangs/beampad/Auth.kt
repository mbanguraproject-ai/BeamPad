package com.devbangs.beampad

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat

/**
 * Authentication gate for revealing or sending secret snippets.
 *
 * Accepts biometrics or the device PIN/pattern/password. On a device with no
 * secure lock screen there is nothing to authenticate against, so secret
 * snippets are refused at save time rather than stored with a gate that
 * cannot hold. Ordinary snippets are unaffected.
 */
object Auth {

    const val NO_LOCK_MESSAGE =
        "Set a screen lock to use protected snippets. " +
            "Without one, anything saved here could be read by anyone holding this phone."

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(ALLOWED) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Prompts the user. [onSuccess] runs only on a genuine pass.
     * [onFail] carries a message when authentication could not be completed.
     */
    fun require(
        activity: FragmentActivity,
        reason: String,
        onSuccess: () -> Unit,
        onFail: (String) -> Unit
    ) {
        if (!isAvailable(activity)) {
            onFail(NO_LOCK_MESSAGE)
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) = onSuccess()

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    onFail(msg.toString())
                }
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("BeamPad")
                .setSubtitle(reason)
                .setAllowedAuthenticators(ALLOWED)
                .build()
        )
    }
}
