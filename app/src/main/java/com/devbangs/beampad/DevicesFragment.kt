package com.devbangs.beampad

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.devbangs.beampad.databinding.FragmentPageBinding

/**
 * Devices: everything BeamPad has connected to or this phone has paired
 * with, each with its state, when it was last used and how it opens, and a
 * one-tap Connect. Tapping a device opens its details and profile.
 */
class DevicesFragment : Fragment() {

    private var _ui: FragmentPageBinding? = null
    private val ui get() = _ui!!

    private val host get() = activity as? MainActivity

    private val connectionObserver: (Boolean) -> Unit = { render() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentPageBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        Ui.header(ui.header.root, getString(R.string.tab_devices), getString(R.string.devices_subtitle))
        Ui.headerButton(ui.header.root, getString(R.string.pair_short), R.drawable.ic_plus) {
            host?.startPairing()
        }
    }

    override fun onStart() {
        super.onStart()
        host?.observeConnection(connectionObserver)
    }

    override fun onStop() {
        super.onStop()
        host?.stopObserving(connectionObserver)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** A row's worth of facts, from the saved record, the Bluetooth bond, or both. */
    private data class Entry(
        val address: String,
        val name: String,
        val type: DeviceType,
        val lastConnected: Long,
        val preferredMode: ControlMode?,
        val bonded: BluetoothDevice?
    )

    private fun entries(): List<Entry> {
        val context = requireContext()
        val service = host?.service
        val saved = DeviceStore(context).all()
        val bonded = service?.pairedHosts().orEmpty()
        val byAddress = bonded.associateBy { it.address }
        val fromSaved = saved.map { d ->
            Entry(d.address, d.displayName, d.type, d.lastConnected, d.preferredMode, byAddress[d.address])
        }
        val savedAddresses = saved.map { it.address }.toSet()
        val onlyBonded = bonded.filter { it.address !in savedAddresses }.map { b ->
            Entry(b.address, service!!.deviceLabel(b), service.typeOf(b), 0L, null, b)
        }
        return fromSaved + onlyBonded
    }

    private fun render() {
        val ui = _ui ?: return
        val context = requireContext()
        val content = ui.content
        content.removeAllViews()
        val service = host?.service
        val list = entries()

        if (list.isEmpty()) {
            Ui.empty(
                content,
                R.drawable.ic_devices,
                getString(R.string.devices_empty_title),
                getString(R.string.devices_empty_body),
                getString(R.string.pair_new)
            ) { host?.startPairing() }
        } else {
            val connected = list.filter { it.address == service?.connectedDevice?.address }
            val others = list - connected.toSet()

            if (connected.isNotEmpty()) {
                Ui.section(content, getString(R.string.devices_connected))
                val card = Ui.card(content)
                connected.forEach { addRow(card, it, isConnected = true) }
            }
            if (others.isNotEmpty()) {
                Ui.section(content, getString(if (connected.isEmpty()) R.string.devices_saved else R.string.devices_other))
                val card = Ui.card(content)
                others.forEachIndexed { i, e ->
                    if (i > 0) Ui.divider(card)
                    addRow(card, e, isConnected = false)
                }
            }
        }

        Ui.section(content, getString(R.string.devices_help))
        val help = Ui.card(content)
        Ui.linkRow(help, getString(R.string.compat_title), getString(R.string.compat_row_body), R.drawable.ic_seal_check) {
            CompatibilityActivity.open(context)
        }
        Ui.divider(help)
        Ui.linkRow(help, getString(R.string.diag_title), getString(R.string.diag_row_body), R.drawable.ic_stethoscope) {
            DiagnosticsActivity.open(context)
        }
    }

    private fun addRow(card: ViewGroup, e: Entry, isConnected: Boolean) {
        val context = requireContext()
        val service = host?.service
        val connecting = service?.connectingDevice?.address == e.address
        val detail = when {
            isConnected -> getString(R.string.state_connected)
            connecting -> getString(R.string.state_connecting)
            e.lastConnected > 0 -> getString(
                R.string.devices_last_used,
                DateUtils.getRelativeTimeSpanString(e.lastConnected, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            )
            e.bonded != null -> getString(R.string.devices_paired_never)
            else -> getString(R.string.devices_not_paired)
        }
        val opens = e.preferredMode?.takeIf { Features.profiles(context) }?.let { getString(it.labelRes) }
        val subtitle = if (opens != null) getString(R.string.devices_detail_opens, detail, opens) else detail

        val row = Ui.row(
            card,
            e.name,
            subtitle,
            e.type.iconRes,
            tone = if (isConnected) Ui.Tone.LIVE else Ui.Tone.NEUTRAL
        ) { DeviceActivity.open(context, e.address) }
        if (isConnected) row.subtitle.setTextColor(context.themeColor(R.attr.bpLive))

        val bonded = e.bonded
        when {
            isConnected -> row.trailing.addView(
                Ui.button(row.trailing, Ui.ButtonKind.SMALL, getString(R.string.action_disconnect)) {
                    host?.disconnect()
                }
            )
            bonded != null && !connecting -> row.trailing.addView(
                Ui.button(row.trailing, Ui.ButtonKind.SMALL_PRIMARY, getString(R.string.action_connect)) {
                    host?.connectTo(bonded)
                    render()
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }
}
