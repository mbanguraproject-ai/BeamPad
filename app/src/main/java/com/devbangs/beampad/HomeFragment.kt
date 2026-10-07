package com.devbangs.beampad

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Home, the blueprint's home screen inside the Control tab: the four large
 * control modes first, then the devices used lately and quick actions
 * (the last panel and saved macros). The active device itself is already
 * in the top bar above. A segment like any other: the app still opens on
 * whatever was used last.
 */
class HomeFragment : Fragment() {

    private var content: LinearLayout? = null
    private val host get() = activity as? MainActivity
    private val control get() = parentFragment as? ControlFragment
    private val observer: (Boolean) -> Unit = { render() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(context, 16))
        }
        content = column
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
        }
    }

    override fun onStart() {
        super.onStart()
        // Answers at once, so this also draws the first time.
        host?.observeConnection(observer)
    }

    override fun onStop() {
        super.onStop()
        host?.stopObserving(observer)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        content = null
    }

    private fun render() {
        val column = content ?: return
        if (context == null) return
        column.removeAllViews()
        modeTiles(column)
        recentDevices(column)
        quickActions(column)
    }

    private fun modeTiles(column: LinearLayout) {
        val context = requireContext()
        val gap = resources.getDimensionPixelSize(R.dimen.gap)
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        listOf(ControlMode.KEYBOARD, ControlMode.TRACKPAD, ControlMode.REMOTE, ControlMode.MEDIA)
            .chunked(2)
            .forEach { pair ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { i, mode ->
                    val tile = layoutInflater.inflate(R.layout.item_mode_tile, row, false)
                    tile.findViewById<ImageView>(R.id.icon).setImageResource(mode.iconRes)
                    tile.findViewById<TextView>(R.id.title).setText(mode.labelRes)
                    tile.findViewById<TextView>(R.id.detail).setText(detail(mode))
                    if (i > 0) (tile.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                    tile.setOnClickListener {
                        Haptics.tick(it)
                        control?.showMode(mode)
                    }
                    row.addView(tile)
                }
                column.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = gutter
                    marginEnd = gutter
                    bottomMargin = gap
                })
            }
    }

    private fun detail(mode: ControlMode) = when (mode) {
        ControlMode.REMOTE -> R.string.ob_mode_remote
        ControlMode.KEYBOARD -> R.string.ob_mode_keyboard
        ControlMode.TRACKPAD -> R.string.ob_mode_trackpad
        else -> R.string.ob_mode_media
    }

    /**
     * Devices used lately, most recent first, each one tap from connected:
     * connect if paired with this phone, otherwise its page.
     */
    private fun recentDevices(column: LinearLayout) {
        val context = requireContext()
        val saved = DeviceStore(context).all().take(MAX_DEVICES)
        Ui.section(column, getString(R.string.home_recent))
        val card = Ui.card(column)
        if (saved.isEmpty()) {
            Ui.row(card, getString(R.string.home_pair), getString(R.string.home_no_devices),
                R.drawable.ic_plus, Ui.Tone.ACCENT) { host?.startPairing() }
            return
        }
        val service = host?.service
        val paired = service?.pairedHosts().orEmpty().associateBy { it.address }
        saved.forEachIndexed { i, d ->
            if (i > 0) Ui.divider(card)
            val device = paired[d.address]
            val connected = service?.connectedDevice?.address == d.address
            val status = when {
                connected -> getString(R.string.state_connected)
                service?.connectingDevice?.address == d.address -> getString(R.string.state_connecting)
                device == null -> getString(R.string.home_not_paired)
                d.lastConnected > 0 -> getString(
                    R.string.home_used,
                    DateUtils.getRelativeTimeSpanString(d.lastConnected, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                )
                else -> getString(d.type.labelRes)
            }
            Ui.row(card, d.displayName, status, d.type.iconRes, if (connected) Ui.Tone.LIVE else Ui.Tone.NEUTRAL) {
                when {
                    connected || device == null -> DeviceActivity.open(context, d.address)
                    else -> host?.connectTo(device)
                }
            }
        }
    }

    /** The last panel and saved macros (Pro), and snippets for everyone. */
    private fun quickActions(column: LinearLayout) {
        val context = requireContext()
        Ui.section(column, getString(R.string.home_quick))
        val card = Ui.card(column)
        if (Features.isPro(context)) {
            val panels = PanelStore(context)
            val panel = Prefs(context).lastPanelId?.let { panels.get(it) } ?: panels.all().firstOrNull()
            if (panel != null) {
                Ui.row(card, panel.name, getString(R.string.home_open_panel), R.drawable.ic_layout, Ui.Tone.ACCENT) {
                    control?.showPanel(panel.id)
                }
                Ui.divider(card)
            }
            MacroStore(context).all().take(MAX_MACROS).forEach { macro ->
                Ui.row(card, macro.name, getString(R.string.home_run_macro), R.drawable.ic_magic_wand, Ui.Tone.ACCENT) {
                    MacroRunSheet.run(requireActivity(), host?.service, macro)
                }
                Ui.divider(card)
            }
        } else {
            Ui.row(card, getString(R.string.home_pro_title), getString(R.string.home_pro_body),
                R.drawable.ic_layout, Ui.Tone.ACCENT) {
                ProActivity.open(context, Features.Pro.PANELS)
            }.pro(true)
            Ui.divider(card)
        }
        Ui.row(card, getString(R.string.tab_snippets), getString(R.string.home_snippets_body),
            R.drawable.ic_lock_key) { MainActivity.openTab(context, R.id.tab_snippets) }
    }

    private companion object {
        const val MAX_DEVICES = 3
        const val MAX_MACROS = 3
    }
}
