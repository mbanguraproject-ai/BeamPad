package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged

/**
 * The command palette (Pro): one search box for everything BeamPad can do.
 * Type "mute", "netflix", "copy" or a device's name and run the match:
 * keys and media commands, the device's apps, macros, panels, control
 * modes, devices, or the typed words themselves, sent as text or as a TV
 * search. Asking for something Bluetooth cannot do (switching HDMI inputs)
 * says so instead of returning nothing.
 */
object CommandPalette {

    private class Command(
        /** Stable, for the recent list. */
        val id: String,
        val title: String,
        val detail: String?,
        val icon: Int?,
        /** Lower-case text the query is matched against. */
        val keywords: String,
        val run: () -> Unit
    )

    private const val FILE = "beampad_commands"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 6
    private const val MAX_RESULTS = 40
    private const val STARTER_APPS = 4
    private const val STARTER_MACROS = 3
    private val INPUT_WORDS = listOf("hdmi", "input", "source")

    fun open(activity: MainActivity) {
        if (!Features.isPro(activity)) {
            ProActivity.open(activity, Features.Pro.COMMANDS)
            return
        }
        val commands = build(activity)
        val sheet = Sheet(activity).title(activity.getString(R.string.cmd_title)).withKeyboard()
        val field = Ui.field(sheet.content, activity.getString(R.string.cmd_search_label),
            hint = activity.getString(R.string.cmd_search_hint))
        field.input.imeOptions = EditorInfo.IME_ACTION_GO
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sheet.content.addView(results)

        var shown: List<Command> = emptyList()
        fun run(command: Command) {
            remember(activity, command.id)
            sheet.dismiss()
            command.run()
        }
        fun render(query: String) {
            results.removeAllViews()
            shown = matches(activity, commands, query)
            if (INPUT_WORDS.any { query.contains(it, ignoreCase = true) }) {
                note(results, activity.getString(R.string.cmd_inputs_unsupported))
            }
            shown.forEach { command -> row(results, command) { run(command) } }
            if (shown.isEmpty() && query.isNotBlank()) note(results, activity.getString(R.string.cmd_none))
        }

        field.input.doAfterTextChanged { render(it?.toString().orEmpty()) }
        field.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                shown.firstOrNull()?.let { run(it) }
                true
            } else false
        }
        render("")
        sheet.show()
        field.input.requestFocus()
    }

    // ---- Matching --------------------------------------------------------------

    private fun matches(activity: MainActivity, all: List<Command>, raw: String): List<Command> {
        val query = raw.trim()
        if (query.isEmpty()) {
            // Recent first, then a short starting set: the modes, the
            // device's first favourite apps (listed first), saved macros.
            val recent = recent(activity).mapNotNull { id -> all.firstOrNull { it.id == id } }
            val starters = all.filter { it.id.startsWith("mode:") } +
                all.filter { it.id.startsWith("app:") }.take(STARTER_APPS) +
                all.filter { it.id.startsWith("macro:") }.take(STARTER_MACROS)
            return (recent + starters).distinctBy { it.id }.take(MAX_RESULTS)
        }
        val words = query.lowercase().split(Regex("\\s+"))
        val found = all
            .filter { c -> words.all { c.keywords.contains(it) } }
            .sortedBy { c ->
                val title = c.title.lowercase()
                when {
                    title.startsWith(query.lowercase()) -> 0
                    title.contains(query.lowercase()) -> 1
                    else -> 2
                }
            }
        return (typed(activity, query) + found).take(MAX_RESULTS)
    }

    /** The query itself: typed out, or searched for on the TV. Built per keystroke. */
    private fun typed(activity: MainActivity, query: String): List<Command> =
        listOf(
            Command("", activity.getString(R.string.cmd_type, query), activity.getString(R.string.cmd_type_detail),
                R.drawable.ic_keyboard, "") {
                ready(activity)?.engine?.keyboard?.type(query) { }
            },
            Command("", activity.getString(R.string.cmd_search_tv, query), activity.getString(R.string.cmd_search_tv_detail),
                R.drawable.ic_magnifying_glass, "") {
                MacroRunSheet.run(activity, activity.service, Macro(
                    name = activity.getString(R.string.cmd_search_tv, query),
                    steps = listOf(
                        Action.Consumer(HidReports.CC_SEARCH), Action.Delay(1200),
                        Action.Text(query), Action.Delay(300),
                        Action.Key(HidReports.KEY_ENTER.toInt())
                    )
                ))
            }
        )

    // ---- The commands ----------------------------------------------------------

    private fun build(activity: MainActivity): List<Command> {
        val list = mutableListOf<Command>()
        val service = activity.service
        val prefs = Prefs(activity)

        ControlMode.entries.forEach { mode ->
            val label = activity.getString(mode.labelRes)
            list += Command("mode:${mode.name}", label, activity.getString(R.string.cmd_kind_mode), mode.iconRes,
                "$label mode surface ${mode.name}".lowercase()) { activity.openControl(mode = mode) }
        }

        val apps = (Launcher.favorites(activity, Launcher.currentDevice(service, prefs)) + Launcher.catalogue)
            .distinctBy { it.id }
        apps.forEach { app ->
            list += Command("app:${app.id}", activity.getString(R.string.launch_opening, app.name),
                activity.getString(R.string.cmd_kind_app), app.logo ?: R.drawable.ic_rocket_launch,
                "${app.name} open app launch".lowercase()) { Launcher.open(activity, activity.service, app) }
        }

        MacroStore(activity).all().forEach { macro ->
            list += Command("macro:${macro.id}", macro.name, activity.getString(R.string.cmd_kind_macro),
                R.drawable.ic_magic_wand, "${macro.name} macro run".lowercase()) {
                MacroRunSheet.run(activity, activity.service, macro)
            }
        }

        PanelStore(activity).all().forEach { panel ->
            list += Command("panel:${panel.id}", panel.name, activity.getString(R.string.cmd_kind_panel),
                R.drawable.ic_layout, "${panel.name} panel".lowercase()) { activity.openControl(panelId = panel.id) }
        }

        Actions.all.forEach { named ->
            val label = Actions.label(activity, named)
            val group = activity.getString(named.group.labelRes)
            list += Command("action:${named.id}", label, group, named.iconRes,
                "$label $group ${named.id.replace('_', ' ')}".lowercase()) {
                ready(activity)?.engine?.perform(named.action)
            }
        }

        if (service != null) service.pairedHosts().forEach { device ->
            val name = service.deviceLabel(device)
            val connected = service.connectedDevice == device
            list += Command("device:${device.address}", activity.getString(
                if (connected) R.string.cmd_disconnect else R.string.cmd_connect, name),
                activity.getString(R.string.cmd_kind_device), service.typeOf(device).iconRes,
                "$name device connect disconnect".lowercase()) {
                if (connected) activity.disconnect() else activity.connectTo(device)
            }
        }

        list += Command("screen:pair", activity.getString(R.string.home_pair), activity.getString(R.string.cmd_kind_screen),
            R.drawable.ic_plus, "pair new device add bluetooth") { activity.startPairing() }
        list += Command("screen:settings", activity.getString(R.string.settings), activity.getString(R.string.cmd_kind_screen),
            R.drawable.ic_gear, "settings preferences options") {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        list += Command("screen:diagnostics", activity.getString(R.string.cmd_diagnostics), activity.getString(R.string.cmd_kind_screen),
            R.drawable.ic_pulse, "diagnostics troubleshoot help connection problem") { DiagnosticsActivity.open(activity) }
        return list
    }

    /** The connected service, or a hint and null. */
    private fun ready(activity: MainActivity): HidService? {
        val service = activity.service
        if (service != null && service.isReady()) return service
        Toast.makeText(activity, R.string.not_connected_hint, Toast.LENGTH_SHORT).show()
        return null
    }

    // ---- Recent ---------------------------------------------------------------

    private fun recent(context: Context): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_RECENT, null)
            ?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    private fun remember(context: Context, id: String) {
        if (id.isEmpty()) return
        val next = (listOf(id) + recent(context).filter { it != id }).take(MAX_RECENT)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT, next.joinToString("\n")).apply()
    }

    // ---- Rows -----------------------------------------------------------------

    private fun row(parent: LinearLayout, command: Command, onClick: () -> Unit) {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.ui_option, parent, false)
        view.findViewById<TextView>(R.id.title).text = command.title
        view.findViewById<TextView>(R.id.detail).apply {
            text = command.detail
            isVisible = !command.detail.isNullOrEmpty()
        }
        view.findViewById<ImageView>(R.id.icon).apply {
            isVisible = command.icon != null
            command.icon?.let { setImageResource(it) }
        }
        view.findViewById<View>(R.id.check).visibility = View.INVISIBLE
        view.setOnClickListener {
            Haptics.tick(it)
            onClick()
        }
        parent.addView(view)
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(TextView(parent.context).apply {
            setTextAppearance(R.style.Text_Small_Faint)
            this.text = text
            val pad = Ui.dp(context, 12)
            setPadding(pad, pad, pad, pad)
        })
    }
}
