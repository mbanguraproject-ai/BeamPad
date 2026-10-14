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

    /**
     * How often a running pass is re-checked. The main-thread timer stops
     * while the phone sleeps, so one long delay would end the pass hours late.
     */
    private const val CHECK_MS = 60_000L

    private const val FILE = "beampad_pass"
    private const val KEY_STARTED = "started"
    private const val KEY_UNTIL = "until"

    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** Open screens were told a pass is running and have not yet been told it ended. */
    private var running = false

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
        watch(context)
    }

    /**
     * Follows a running pass so open screens lock its features when it runs
     * out, not at the next screen change. Called as the process starts and
     * on each grant. The gates read the clock on every call, so a late
     * check never extends a pass; it only delays the announcement.
     */
    fun watch(context: Context) {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        val left = remainingMs(context)
        if (left <= 0) return
        running = true
        val app = app(context)
        val task = Runnable {
            tick = null
            check(app)
        }
        tick = task
        main.postDelayed(task, minOf(left, CHECK_MS))
    }

    /**
     * Tells open screens, once, that the pass has ended. Run by the timer
     * and as each screen resumes, so a phone that slept through the end, or
     * a clock that was changed, is caught at once.
     */
    fun check(context: Context) {
        if (!running) return
        if (active(context)) {
            if (tick == null) watch(context)
            return
        }
        running = false
        tick?.let { main.removeCallbacks(it) }
        tick = null
        val app = app(context)
        // Bought Pro meanwhile: nothing ends.
        if (!app.entitlements.isPro) app.notifyEntitlement(Entitlements.Change.PASS_ENDED)
    }

    private fun app(context: Context) = context.applicationContext as BeamPadApp

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
