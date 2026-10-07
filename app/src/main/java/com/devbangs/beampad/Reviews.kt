package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Google Play in-app review, asked only after BeamPad has demonstrably
 * worked three times: a session that reached the device, a macro that
 * finished, a snippet that was sent. Asked at those natural pauses, never
 * while a control surface is in use.
 *
 * Play quota-limits the dialog per user, so most calls show nothing. That is
 * expected, not a failure: never retry, never prompt the user to rate, and
 * never gate the request on how they felt about the app. All three are
 * policy violations.
 */
object Reviews {

    private const val PREFS = "beampad_review"
    // Key kept from the first version so earlier successes still count.
    private const val KEY_SUCCESSES = "good_sessions"
    private const val KEY_ASKED = "asked"

    /** Successful actions before asking. */
    private const val THRESHOLD = 3

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Counts one thing that worked. */
    fun recordSuccess(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit().putInt(KEY_SUCCESSES, prefs.getInt(KEY_SUCCESSES, 0) + 1).apply()
    }

    /** Called when a session ends having actually sent input. */
    fun recordGoodSession(context: Context) = recordSuccess(context)

    /** Counts a success and, if that makes enough, asks a moment later. */
    fun success(activity: Activity) {
        recordSuccess(activity)
        activity.window?.decorView?.postDelayed({ ask(activity) }, ASK_DELAY_MS)
    }

    fun shouldAsk(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_ASKED, false)) return false
        return prefs.getInt(KEY_SUCCESSES, 0) >= THRESHOLD
    }

    fun ask(activity: Activity) {
        if (activity.isFinishing || !shouldAsk(activity)) return

        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful || activity.isFinishing) return@addOnCompleteListener
            manager.launchReviewFlow(activity, request.result)
                .addOnCompleteListener {
                    // Always marked as asked: the API never reveals whether
                    // the dialog appeared or what the user did, and asking
                    // repeatedly is the thing Play penalises.
                    prefs(activity).edit().putBoolean(KEY_ASKED, true).apply()
                }
        }
    }

    private const val ASK_DELAY_MS = 700L
}
