package com.devbangs.beampad

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import java.util.Date

/**
 * Connection diagnostics: every condition BeamPad depends on, checked
 * live, each with a plain explanation and the one action that fixes it,
 * plus the recent connection history. A premium utility never leaves the
 * user asking why a button stopped working.
 */
class DiagnosticsActivity : PageActivity() {

    override val wantsService = true

    override fun title(): CharSequence = getString(R.string.diag_title)

    private enum class Result(val icon: Int, val tone: Ui.Tone) {
        OK(R.drawable.ic_check_circle, Ui.Tone.LIVE),
        WARN(R.drawable.ic_warning_circle, Ui.Tone.DANGER),
        INFO(R.drawable.ic_info, Ui.Tone.NEUTRAL)
    }

    override fun render() {
        val content = page.content
        val s = service
        val adapter = runCatching { getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val btOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val permitted = hasNearbyPermission()

        Ui.section(content, getString(R.string.diag_checks))
        val checks = Ui.card(content)

        check(checks,
            if (adapter != null) Result.OK else Result.WARN,
            getString(R.string.diag_hw),
            getString(if (adapter != null) R.string.diag_hw_ok else R.string.diag_hw_missing))

        check(checks,
            if (btOn) Result.OK else Result.WARN,
            getString(R.string.diag_bt),
            getString(if (btOn) R.string.diag_bt_on else R.string.diag_bt_off),
            action = if (!btOn) getString(R.string.action_turn_on) to { openBluetoothSettings() } else null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            check(checks,
                if (permitted) Result.OK else Result.WARN,
                getString(R.string.diag_permission),
                getString(if (permitted) R.string.diag_permission_ok else R.string.diag_permission_missing),
                action = if (!permitted) getString(R.string.action_allow) to { openAppSettings() } else null)
        }

        val (hidResult, hidDetail) = when (s?.state) {
            null -> Result.INFO to getString(R.string.diag_hid_idle)
            HidService.State.UNSUPPORTED -> Result.WARN to getString(
                when (s.unsupportedReason) {
                    HidService.UnsupportedReason.NO_PROFILE -> R.string.unsupported_no_profile
                    HidService.UnsupportedReason.REFUSED -> R.string.unsupported_refused
                    else -> R.string.unsupported_no_response
                }
            )
            HidService.State.STARTING -> Result.INFO to getString(R.string.status_starting_detail)
            HidService.State.NO_BLUETOOTH, HidService.State.BLUETOOTH_OFF -> Result.INFO to getString(R.string.diag_hid_waiting_bt)
            else -> Result.OK to getString(R.string.diag_hid_ok)
        }
        check(checks, hidResult, getString(R.string.diag_hid), hidDetail,
            action = if (s?.state == HidService.State.UNSUPPORTED &&
                s.unsupportedReason != HidService.UnsupportedReason.NO_PROFILE
            ) getString(R.string.action_try_again) to { s.restart(); refresh() } else null)

        val (connResult, connDetail) = when {
            s?.connectedDevice != null -> Result.OK to getString(R.string.diag_conn_ok, s.deviceLabel(s.connectedDevice!!))
            s?.connectingDevice != null -> Result.INFO to getString(R.string.status_connecting_to, s.deviceLabel(s.connectingDevice!!))
            s?.lostDevice != null -> Result.WARN to getString(R.string.status_lost_detail, s.deviceLabel(s.lostDevice!!))
            else -> Result.INFO to getString(R.string.diag_conn_none)
        }
        check(checks, connResult, getString(R.string.diag_conn), connDetail,
            action = if (s?.lostDevice != null) getString(R.string.action_reconnect) to { s.reconnect(); refresh() } else null)

        val power = getSystemService(PowerManager::class.java)
        val unrestricted = power?.isIgnoringBatteryOptimizations(packageName) == true
        check(checks,
            if (unrestricted) Result.OK else Result.INFO,
            getString(R.string.diag_battery),
            getString(if (unrestricted) R.string.diag_battery_ok else R.string.diag_battery_restricted),
            action = if (!unrestricted) getString(R.string.diag_battery_action) to { openBatterySettings() } else null)

        // Recent history.
        Ui.section(content, getString(R.string.diag_history))
        val history = Ui.card(content)
        val entries = ConnectionLog.entries(this).take(HISTORY_ROWS)
        if (entries.isEmpty()) {
            Ui.row(history, getString(R.string.diag_history_empty), getString(R.string.diag_history_empty_body), R.drawable.ic_clock)
        } else {
            entries.forEachIndexed { i, e ->
                if (i > 0) Ui.divider(history)
                val time = DateFormat.getTimeFormat(this).format(Date(e.at))
                val day = DateFormat.getMediumDateFormat(this).format(Date(e.at))
                Ui.row(history, kindLabel(e.kind), listOfNotNull(e.detail.takeIf { it.isNotBlank() }, "$day $time").joinToString(" · "),
                    kindIcon(e.kind), if (e.kind in BAD_KINDS) Ui.Tone.DANGER else Ui.Tone.NEUTRAL)
            }
        }

        Ui.section(content, getString(R.string.diag_actions))
        val actions = Ui.card(content)
        Ui.linkRow(actions, getString(R.string.diag_share), getString(R.string.diag_share_body), R.drawable.ic_share_network) {
            shareLog()
        }
        Ui.divider(actions)
        Ui.linkRow(actions, getString(R.string.diag_open_bt), null, R.drawable.ic_bluetooth) { openBluetoothSettings() }
        Ui.divider(actions)
        Ui.linkRow(actions, getString(R.string.compat_title), getString(R.string.compat_row_body), R.drawable.ic_seal_check) {
            CompatibilityActivity.open(this)
        }
        if (entries.isNotEmpty()) {
            Ui.divider(actions)
            Ui.row(actions, getString(R.string.diag_clear), null, R.drawable.ic_trash) {
                ConnectionLog.clear(this)
                refresh()
            }
        }
    }

    private fun check(
        parent: ViewGroup,
        result: Result,
        title: String,
        detail: String,
        action: Pair<String, () -> Unit>? = null
    ) {
        if (parent.childCount > 0) Ui.divider(parent)
        val row = Ui.row(parent, title, detail, result.icon, result.tone)
        row.subtitle.maxLines = 5
        if (action != null) {
            row.trailing.addView(Ui.button(row.trailing, Ui.ButtonKind.SMALL_PRIMARY, action.first) { action.second() })
        }
    }

    private fun kindLabel(kind: ConnectionLog.Kind): String = getString(
        when (kind) {
            ConnectionLog.Kind.BLUETOOTH -> R.string.log_bluetooth
            ConnectionLog.Kind.REGISTERED -> R.string.log_registered
            ConnectionLog.Kind.UNSUPPORTED -> R.string.log_unsupported
            ConnectionLog.Kind.CONNECTING -> R.string.log_connecting
            ConnectionLog.Kind.CONNECTED -> R.string.log_connected
            ConnectionLog.Kind.DISCONNECTED -> R.string.log_disconnected
            ConnectionLog.Kind.LOST -> R.string.log_lost
            ConnectionLog.Kind.RETRY -> R.string.log_retry
            ConnectionLog.Kind.FAILED -> R.string.log_failed
            ConnectionLog.Kind.SEND_FAILED -> R.string.log_send_failed
        }
    )

    private fun kindIcon(kind: ConnectionLog.Kind): Int = when (kind) {
        ConnectionLog.Kind.CONNECTED -> R.drawable.ic_bluetooth_connected
        ConnectionLog.Kind.BLUETOOTH -> R.drawable.ic_bluetooth
        ConnectionLog.Kind.LOST, ConnectionLog.Kind.FAILED, ConnectionLog.Kind.UNSUPPORTED,
        ConnectionLog.Kind.SEND_FAILED -> R.drawable.ic_warning_circle
        ConnectionLog.Kind.RETRY -> R.drawable.ic_arrow_clockwise
        else -> R.drawable.ic_pulse
    }

    private fun hasNearbyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    /** Only when the user taps Share: the log never leaves the phone otherwise. */
    private fun shareLog() {
        val lines = ConnectionLog.entries(this).joinToString("\n") { e ->
            val at = DateFormat.format("yyyy-MM-dd HH:mm:ss", e.at)
            "$at  ${e.kind.name}  ${e.detail}"
        }
        val header = "BeamPad ${packageManager.getPackageInfo(packageName, 0).versionName} · " +
            "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "State: ${service?.state ?: "not running"}\n\n"
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_share_subject))
            .putExtra(Intent.EXTRA_TEXT, header + lines.ifEmpty { getString(R.string.diag_history_empty) })
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.diag_share))) }
    }

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    companion object {
        private const val HISTORY_ROWS = 25
        private val BAD_KINDS = setOf(
            ConnectionLog.Kind.LOST, ConnectionLog.Kind.FAILED,
            ConnectionLog.Kind.UNSUPPORTED, ConnectionLog.Kind.SEND_FAILED
        )

        fun open(context: Context) {
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
    }
}
