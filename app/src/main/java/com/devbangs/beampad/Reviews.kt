package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * In-app review, asked only after the app has demonstrably worked.
 *
 * Play quota-limits the dialog per user, so most calls show nothing. That is
 * expected, not a failure: never retry, never prompt the user to rate, and
 * never gate the request on how they felt about the app. All three are
 * policy violations.
 */
object Reviews {

    private const val PREFS = "beampad_review"
    private const val KEY_SESSIONS = "good_sessions"
    private const val KEY_ASKED = "asked"

    /** Successful sessions before asking. */
    private const val THRESHOLD = 3

    /** Called when a session ends having actually sent input. */
    fun recordGoodSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit()
            .putInt(KEY_SESSIONS, prefs.getInt(KEY_SESSIONS, 0) + 1)
            .apply()
    }

    fun shouldAsk(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return false
        return prefs.getInt(KEY_SESSIONS, 0) >= THRESHOLD
    }

    fun ask(activity: Activity) {
        if (!shouldAsk(activity)) return

        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) return@addOnCompleteListener
            manager.launchReviewFlow(activity, request.result)
                .addOnCompleteListener {
                    // Always marked as asked: the API never reveals whether
                    // the dialog appeared or what the user did, and asking
                    // repeatedly is the thing Play penalises.
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_ASKED, true)
                        .apply()
                }
        }
    }
}
