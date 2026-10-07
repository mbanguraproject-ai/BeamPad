package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * One device: its name, icon and labels, and its profile (which control
 * surface opens on connect, its keyboard layout). Identity is free;
 * profiles are Pro, per the blueprint's monetisation table.
 */
class DeviceActivity : PageActivity() {

    override val wantsService = true

    private val address: String get() = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
    private val store by lazy { DeviceStore(this) }

    /** The saved record, or a fresh one built from the Bluetooth bond. */
    private fun device(): SavedDevice {
        store.get(address)?.let { return it }
        val bonded = service?.pairedHosts()?.firstOrNull { it.address == address }
        return SavedDevice(
            address = address,
            systemName = bonded?.let { service?.deviceLabel(it) } ?: address,
            type = bonded?.let { service?.typeOf(it) } ?: DeviceType.TV
        )
    }

    private fun save(update: (SavedDevice) -> SavedDevice) {
        store.save(update(device()))
        service?.refreshProfile()
        refresh()
    }

    override fun title(): CharSequence = device().displayName
    override fun subtitle(): CharSequence = getString(device().type.labelRes)

    override fun render() {
        val d = device()
        val content = page.content
        val s = service
        val isConnected = s?.connectedDevice?.address == address
        val bonded = s?.pairedHosts()?.firstOrNull { it.address == address }

        // Hero: what it is and whether it is connected, with the one action that matters.
        val hero = LayoutInflater.from(this).inflate(R.layout.view_device_hero, content, false)
        hero.findViewById<ImageView>(R.id.icon).setImageResource(d.type.iconRes)
        hero.findViewById<FrameLayout>(R.id.tile).setBackgroundResource(
            if (isConnected) R.drawable.bg_icon_tile_live else R.drawable.bg_icon_tile
        )
        hero.findViewById<ImageView>(R.id.icon).imageTintList = android.content.res.ColorStateList.valueOf(
            themeColor(if (isConnected) R.attr.bpLive else R.attr.bpText)
        )
        hero.findViewById<TextView>(R.id.name).text = d.displayName
        hero.findViewById<TextView>(R.id.status).apply {
            text = when {
                isConnected -> getString(R.string.state_connected)
                s?.connectingDevice?.address == address -> getString(R.string.state_connecting)
                d.lastConnected > 0 -> getString(
                    R.string.devices_last_used,
                    DateUtils.getRelativeTimeSpanString(d.lastConnected, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                )
                bonded != null -> getString(R.string.devices_paired_never)
                else -> getString(R.string.devices_not_paired)
            }
            setTextColor(themeColor(if (isConnected) R.attr.bpLive else R.attr.bpTextDim))
        }
        if (d.customName != null && d.customName != d.systemName) {
            hero.findViewById<TextView>(R.id.systemName).apply {
                isVisible = true
                text = getString(R.string.device_bluetooth_name, d.systemName)
            }
        }
        val actions = hero.findViewById<LinearLayout>(R.id.actions)
        val action = when {
            isConnected -> Ui.button(actions, Ui.ButtonKind.SECONDARY, getString(R.string.action_disconnect)) {
                s?.disconnect()
            }
            bonded != null -> Ui.button(actions, Ui.ButtonKind.PRIMARY, getString(R.string.action_connect)) {
                s?.connect(bonded)
                refresh()
            }
            else -> null
        }
        action?.let {
            actions.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        actions.isVisible = action != null
        content.addView(hero)

        // Identity: free.
        Ui.section(content, getString(R.string.device_identity))
        val identity = Ui.card(content)
        Ui.valueRow(identity, getString(R.string.device_name), d.displayName, icon = R.drawable.ic_pencil_simple) {
            Sheets.input(
                this,
                getString(R.string.device_name),
                getString(R.string.device_name_label),
                d.customName ?: d.systemName,
                hint = d.systemName
            ) { name -> save { it.copy(customName = name.takeIf { n -> n != it.systemName }) } }
        }
        Ui.divider(identity)
        Ui.valueRow(identity, getString(R.string.device_type), getString(d.type.labelRes), icon = d.type.iconRes) {
            Sheets.choose(
                this,
                getString(R.string.device_type),
                DeviceType.entries.map { Sheets.Choice(it, getString(it.labelRes), icon = it.iconRes) },
                d.type
            ) { type -> save { it.copy(type = type) } }
        }
        Ui.divider(identity)
        val labelsRow = Ui.row(identity, getString(R.string.device_labels),
            if (d.labels.isEmpty()) getString(R.string.device_labels_none) else null, R.drawable.ic_tag) {
            addLabel()
        }
        labelsRow.trailing.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            imageTintList = android.content.res.ColorStateList.valueOf(themeColor(R.attr.bpTextDim))
            layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18))
        })
        if (d.labels.isNotEmpty()) {
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(Ui.dp(context, 64), 0, Ui.dp(context, 16), Ui.dp(context, 14))
            }
            d.labels.forEach { label ->
                Ui.chip(chips, "$label  ✕", selected = false) {
                    save { it.copy(labels = it.labels - label) }
                }
            }
            identity.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
        }

        // Profile: Pro.
        val pro = Features.profiles(this)
        Ui.section(content, getString(R.string.device_profile))
        val profile = Ui.card(content)
        val opensWith = when {
            !pro -> getString(R.string.device_opens_default)
            d.defaultPanelId != null -> PanelStore(this).get(d.defaultPanelId)?.name ?: getString(R.string.device_opens_default)
            d.preferredMode != null -> getString(d.preferredMode.labelRes)
            else -> getString(R.string.device_opens_default)
        }
        // Nothing chosen yet: say what suits this kind of device.
        val suggestion = if (d.preferredMode == null && d.defaultPanelId == null) {
            getString(R.string.device_suggested_hint, getString(d.type.suggestedMode.labelRes))
        } else null
        val (opensRow, _) = Ui.valueRow(profile, getString(R.string.device_opens_with), opensWith,
            subtitle = suggestion, icon = R.drawable.ic_lightning) {
            if (requirePro()) pickOpensWith(d)
        }
        opensRow.pro(!pro)
        Ui.divider(profile)
        val layoutValue = (if (pro) d.layout else null)?.label ?: getString(R.string.device_layout_default_short)
        val (layoutRow, _) = Ui.valueRow(profile, getString(R.string.device_layout), layoutValue,
            icon = R.drawable.ic_keyboard) {
            if (requirePro()) pickLayout(d)
        }
        layoutRow.pro(!pro)
        Ui.divider(profile)
        val macroName = (if (pro) d.connectMacroId else null)?.let { MacroStore(this).get(it)?.name } ?: getString(R.string.none)
        val (macroRow, _) = Ui.valueRow(profile, getString(R.string.device_connect_macro), macroName,
            icon = R.drawable.ic_magic_wand) {
            if (requirePro()) pickConnectMacro(d)
        }
        macroRow.pro(!pro)

        // Forget.
        Ui.section(content, getString(R.string.device_manage))
        val manage = Ui.card(content)
        if (store.get(address) != null) {
            Ui.row(manage, getString(R.string.device_forget), getString(R.string.device_forget_body),
                R.drawable.ic_trash, Ui.Tone.DANGER) {
                Sheets.confirm(
                    this,
                    getString(R.string.device_forget_confirm, d.displayName),
                    getString(R.string.device_forget_confirm_body),
                    getString(R.string.device_forget),
                    destructive = true
                ) {
                    store.remove(address)
                    if (prefs.lastHost == address) prefs.lastHost = null
                    finish()
                }
            }.title.setTextColor(themeColor(R.attr.bpDanger))
            Ui.divider(manage)
        }
        Ui.linkRow(manage, getString(R.string.device_unpair), getString(R.string.device_unpair_body), R.drawable.ic_bluetooth) {
            runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    private fun requirePro(): Boolean {
        if (Features.profiles(this)) return true
        ProActivity.open(this, Features.Pro.PROFILES)
        return false
    }

    private fun addLabel() {
        Sheets.input(
            this,
            getString(R.string.device_label_add),
            getString(R.string.device_label_label),
            hint = getString(R.string.device_label_hint)
        ) { label -> save { it.copy(labels = (it.labels + label).distinct()) } }
    }

    /** What opens on connect: the app default, a built-in mode, or one of the user's panels. */
    private sealed interface Opens {
        data object Default : Opens
        data class Mode(val mode: ControlMode) : Opens
        data class PanelId(val id: String) : Opens
    }

    private fun pickOpensWith(d: SavedDevice) {
        val choices = mutableListOf<Sheets.Choice<Opens>>(
            Sheets.Choice(Opens.Default, getString(R.string.device_opens_default), getString(R.string.device_opens_default_body))
        )
        ControlMode.entries.forEach {
            val note = if (it == d.type.suggestedMode) getString(R.string.device_suggested_choice) else null
            choices += Sheets.Choice(Opens.Mode(it), getString(it.labelRes), note, it.iconRes)
        }
        PanelStore(this).all().forEach {
            choices += Sheets.Choice(Opens.PanelId(it.id), it.name, getString(R.string.device_opens_panel), R.drawable.ic_layout)
        }
        val current: Opens = when {
            d.defaultPanelId != null -> Opens.PanelId(d.defaultPanelId)
            d.preferredMode != null -> Opens.Mode(d.preferredMode)
            else -> Opens.Default
        }
        Sheets.choose(this, getString(R.string.device_opens_with), choices, current,
            subtitle = getString(R.string.device_opens_with_body)) { pick ->
            save {
                when (pick) {
                    Opens.Default -> it.copy(preferredMode = null, defaultPanelId = null)
                    is Opens.Mode -> it.copy(preferredMode = pick.mode, defaultPanelId = null)
                    is Opens.PanelId -> it.copy(preferredMode = null, defaultPanelId = pick.id)
                }
            }
        }
    }

    private fun pickConnectMacro(d: SavedDevice) {
        val macros = MacroStore(this).all()
        if (macros.isEmpty()) {
            Sheets.confirm(this, getString(R.string.panel_no_macros), getString(R.string.device_connect_macro_none),
                getString(R.string.macro_new)) { MacrosActivity.open(this) }
            return
        }
        val choices = listOf(Sheets.Choice<String?>(null, getString(R.string.none))) +
            macros.map { Sheets.Choice<String?>(it.id, it.name, resources.getQuantityString(R.plurals.macro_steps, it.steps.size, it.steps.size), R.drawable.ic_magic_wand) }
        Sheets.choose(this, getString(R.string.device_connect_macro), choices, d.connectMacroId,
            subtitle = getString(R.string.device_connect_macro_body)) { id ->
            save { it.copy(connectMacroId = id) }
        }
    }

    private fun pickLayout(d: SavedDevice) {
        val choices = listOf(Sheets.Choice<HidReports.Layout?>(null, getString(R.string.device_layout_default, prefs.layout.label))) +
            HidReports.Layout.entries.map { Sheets.Choice<HidReports.Layout?>(it, it.label) }
        Sheets.choose(this, getString(R.string.device_layout), choices, d.layout,
            subtitle = getString(R.string.device_layout_body)) { layout ->
            save { it.copy(layout = layout) }
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"

        fun open(context: Context, address: String) {
            context.startActivity(Intent(context, DeviceActivity::class.java).putExtra(EXTRA_ADDRESS, address))
        }
    }
}
