package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Google Play in-app updates. A new release downloads in the background
 * (flexible) and BeamPad offers a restart once it is ready, so nobody is
 * interrupted mid-control. Releases given a high update priority in Play
 * (4 or 5) use the full-screen immediate flow instead.
 *
 * The flexible prompt appears at most once a day.
 */
object Updates {

    private const val PREFS = "beampad_updates"
    private const val KEY_LAST_PROMPT_DAY = "last_prompt_day"
    private const val DAY_MS = 86_400_000L
    private const val URGENT_PRIORITY = 4

    private var manager: AppUpdateManager? = null
    private var listener: InstallStateUpdatedListener? = null

    private fun manager(context: Context): AppUpdateManager =
        manager ?: AppUpdateManagerFactory.create(context.applicationContext).also { manager = it }

    /** Checks Play for an update; call as the main screen opens. */
    fun check(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val manager = runCatching { manager(activity) }.getOrNull() ?: return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (activity.isFinishing) return@addOnSuccessListener
            when {
                info.installStatus() == InstallStatus.DOWNLOADED -> offerRestart(activity)

                // An immediate update the user left part-way: resume it.
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                    start(manager, info, launcher, AppUpdateType.IMMEDIATE)

                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE -> {
                    val urgent = info.updatePriority() >= URGENT_PRIORITY &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    when {
                        urgent -> start(manager, info, launcher, AppUpdateType.IMMEDIATE)
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) && promptAllowedToday(activity) -> {
                            watchDownload(activity, manager)
                            start(manager, info, launcher, AppUpdateType.FLEXIBLE)
                        }
                    }
                }
            }
        }
    }

    /** On return to the app: a flexible update may have finished downloading meanwhile. */
    fun resume(activity: Activity) {
        val manager = runCatching { manager(activity) }.getOrNull() ?: return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (!activity.isFinishing && info.installStatus() == InstallStatus.DOWNLOADED) offerRestart(activity)
        }
    }

    private fun start(
        manager: AppUpdateManager,
        info: com.google.android.play.core.appupdate.AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        type: Int
    ) {
        runCatching { manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build()) }
    }

    private fun watchDownload(activity: Activity, manager: AppUpdateManager) {
        listener?.let { manager.unregisterListener(it) }
        val l = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                listener?.let { manager.unregisterListener(it) }
                listener = null
                if (!activity.isFinishing) offerRestart(activity)
            }
        }
        listener = l
        manager.registerListener(l)
    }

    private fun offerRestart(activity: Activity) {
        val manager = manager ?: return
        Sheet(activity)
            .title(activity.getString(R.string.update_ready_title))
            .subtitle(activity.getString(R.string.update_ready_body))
            .secondary(activity.getString(R.string.update_later))
            .primary(activity.getString(R.string.update_restart)) {
                manager.completeUpdate()
                true
            }
            .show()
    }

    private fun promptAllowedToday(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / DAY_MS
        if (prefs.getLong(KEY_LAST_PROMPT_DAY, -1) == today) return false
        prefs.edit().putLong(KEY_LAST_PROMPT_DAY, today).apply()
        return true
    }
}
