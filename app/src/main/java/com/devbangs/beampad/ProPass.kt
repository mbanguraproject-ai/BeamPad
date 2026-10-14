package com.devbangs.beampad

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pro for a day, earned by watching a rewarded ad on the Pro page
 * ([RewardedAds]).
 *
 * A pass unlocks the Pro features ([Features.isPro]) but not ad removal,
 * and it is not a purchase: [Entitlements] stays the record of what was
 * paid for, and the Pro page keeps selling Pro while a pass runs. Another
 * pass can be earned once this one has ended.
 *
 * Kept on this phone only (excluded from backups). Timed by the wall clock
 * so it survives restarts; a clock set back to before the grant ends the
 * pass rather than stretching it.
 */
object ProPass {

    const val DURATION_MS = 24 * 60 * 60 * 1000L
    private const val HOUR_MS = 60 * 60 * 1000L

    private const val FILE = "beampad_pass"
    private const val KEY_STARTED = "started"
    private const val KEY_UNTIL = "until"

    private val main = Handler(Looper.getMainLooper())
    private var end: Runnable? = null

    fun active(context: Context): Boolean = remainingMs(context) > 0

    /** Time left on the pass; 0 when there is none. */
    fun remainingMs(context: Context): Long {
        val store = prefs(context)
        val now = System.currentTimeMillis()
        val started = store.getLong(KEY_STARTED, 0L)
        val endsAt = store.getLong(KEY_UNTIL, 0L)
        return if (now in started until endsAt) endsAt - now else 0L
    }

    /** What is left of a pass, in whole hours rounded up, as a sentence. */
    fun hoursLeftText(context: Context, remainingMs: Long): String {
        val hours = ((remainingMs + HOUR_MS - 1) / HOUR_MS).toInt().coerceAtLeast(1)
        return context.resources.getQuantityString(R.plurals.pass_hours_left, hours, hours)
    }

    /** Starts a pass for an earned reward, and tells open screens that Pro features are on. */
    fun grant(context: Context) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_STARTED, now)
            .putLong(KEY_UNTIL, now + DURATION_MS)
            .apply()
        app(context).notifyEntitlement(Entitlements.Change.PASS_STARTED)
        scheduleEnd(context)
    }

    /**
     * Tells open screens when the pass runs out, so features lock while the
     * app is in use rather than at the next screen change. Called as the
     * process starts and on each grant. The gates themselves read the clock
     * every time, so a late timer (the phone asleep) never extends a pass.
     */
    fun scheduleEnd(context: Context) {
        end?.let { main.removeCallbacks(it) }
        end = null
        val left = remainingMs(context)
        if (left <= 0) return
        val app = app(context)
        val task = Runnable {
            end = null
            when {
                active(app) -> scheduleEnd(app)
                // Bought Pro meanwhile: nothing ends.
                !app.entitlements.isPro -> app.notifyEntitlement(Entitlements.Change.PASS_ENDED)
            }
        }
        end = task
        main.postDelayed(task, left)
    }

    private fun app(context: Context) = context.applicationContext as BeamPadApp

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
