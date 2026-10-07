package com.devbangs.beampad

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment

/**
 * Launch (Pro), the blueprint's Launch mode: the current device's favourite
 * apps as tiles, each opened in one tap, and Edit to choose them. Pro is
 * checked by the Control tab before this page is shown.
 */
class LaunchFragment : Fragment() {

    private var content: LinearLayout? = null
    private val host get() = activity as? MainActivity
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
        // Answers at once, so this also draws the first time; and the
        // favourites follow the device when another one connects.
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

    private fun device(): String? = Launcher.currentDevice(host?.service, Prefs(requireContext()))

    private fun render() {
        val column = content ?: return
        val context = context ?: return
        column.removeAllViews()
        val address = device()
        val name = address?.let { DeviceStore(context).get(it)?.displayName }
        Ui.section(column, if (name != null) getString(R.string.launch_for, name) else getString(R.string.launch_title))

        val tiles = Launcher.favorites(context, address).map { app ->
            tile(app, app.name) { Launcher.open(requireActivity(), host?.service, app) }
        } + tile(null, getString(R.string.launch_edit)) { edit() }

        val gap = resources.getDimensionPixelSize(R.dimen.gap)
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        tiles.chunked(COLUMNS).forEach { row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, tile ->
                if (i > 0) (tile.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                line.addView(tile)
            }
            // Keep the last row's tiles the same width as the others.
            repeat(COLUMNS - row.size) {
                line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = gap })
            }
            column.addView(line, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = gutter
                marginEnd = gutter
                bottomMargin = gap
            })
        }

        column.addView(TextView(context).apply {
            setTextAppearance(R.style.Text_Small_Faint)
            text = getString(R.string.launch_note)
            setPadding(gutter, Ui.dp(context, 8), gutter, 0)
        })
    }

    /**
     * One tile: a known app's logo on its own colours, an initial for apps
     * added by name, or the Edit pencil when [app] is null.
     */
    private fun tile(app: TvApp?, label: String, onClick: () -> Unit): View {
        val view = layoutInflater.inflate(R.layout.item_app_tile, content, false)
        val monogram = view.findViewById<TextView>(R.id.monogram)
        val icon = view.findViewById<ImageView>(R.id.icon)
        val logo = app?.logo
        icon.isVisible = app == null || logo != null
        monogram.isVisible = app != null && logo == null
        if (app == null) icon.setImageResource(R.drawable.ic_pencil_simple)
        if (logo != null) icon.setImageResource(logo)
        if (app != null) monogram.text = app.name.take(1).uppercase()
        val tile = app?.tile
        if (app != null && tile != null) {
            val ink = ColorStateList.valueOf(app.ink ?: Color.WHITE)
            icon.imageTintList = ink
            icon.updateLayoutParams { width = Ui.dp(requireContext(), LOGO_DP); height = width }
            monogram.setTextColor(ink)
            // Bold, so a brand initial holds its own beside the real logos.
            monogram.setTypeface(monogram.typeface, android.graphics.Typeface.BOLD)
            view.findViewById<View>(R.id.tile).background = GradientDrawable().apply {
                cornerRadius = Ui.dp(requireContext(), TILE_RADIUS_DP).toFloat()
                setColor(tile)
                // A hairline keeps black and white tiles distinct on either theme.
                setStroke(Ui.dp(requireContext(), 1), requireContext().themeColor(R.attr.bpStroke))
            }
        }
        view.findViewById<TextView>(R.id.name).text = label
        view.contentDescription = label
        view.setOnClickListener {
            Haptics.tick(it)
            onClick()
        }
        return view
    }

    /** Which apps show for this device: the catalogue and any added by name, switched on or off. */
    private fun edit() {
        val context = requireContext()
        val address = device()
        val chosen = Launcher.favorites(context, address).toMutableList()
        val offered = (Launcher.catalogue + chosen).distinctBy { it.id }

        val sheet = Sheet(context)
            .title(getString(R.string.launch_edit_title))
            .subtitle(getString(R.string.launch_edit_body))
        offered.forEach { app ->
            Ui.switchRow(sheet.content, app.name, null, null, chosen.any { it.id == app.id }) { on ->
                if (on) chosen += app else chosen.removeAll { it.id == app.id }
                on
            }
        }
        Ui.row(sheet.content, getString(R.string.launch_add), getString(R.string.launch_add_body),
            R.drawable.ic_plus, Ui.Tone.ACCENT) {
            sheet.dismiss()
            Sheets.input(context, getString(R.string.launch_add), getString(R.string.launch_add_label),
                hint = getString(R.string.launch_add_hint)) { name ->
                if (name.isNotBlank()) {
                    Launcher.setFavorites(context, address, chosen + Launcher.custom(name))
                    render()
                }
            }
        }
        sheet.primary(getString(R.string.done)) {
            Launcher.setFavorites(context, address, chosen)
            render()
            true
        }
        sheet.show()
    }

    private companion object {
        const val COLUMNS = 3
        const val LOGO_DP = 26
        const val TILE_RADIUS_DP = 14
    }
}
