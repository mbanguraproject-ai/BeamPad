package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible

/**
 * Builds a custom panel. The panel shows exactly as it will on the
 * Control tab; tap a control to change what it does, its label and size,
 * long-press to drag it somewhere else, and Add places any of the ten
 * component types. Every change saves as it happens.
 */
class PanelEditorActivity : PageActivity() {

    private val panelId: String get() = intent.getStringExtra(EXTRA_PANEL).orEmpty()
    private val store by lazy { PanelStore(this) }
    private lateinit var grid: PanelGrid

    private fun panel(): Panel? = store.get(panelId)

    override fun title(): CharSequence = panel()?.name ?: getString(R.string.tab_panels)

    override fun subtitle(): CharSequence {
        val n = panel()?.components?.size ?: 0
        return if (n == 0) getString(R.string.panel_editor_hint_empty)
        else resources.getQuantityString(R.plurals.panel_editor_hint, n, n)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (panel() == null) {
            finish()
            return
        }
        page.scroll.isVisible = false
        grid = PanelGrid(this).apply {
            editing = true
            val side = resources.getDimensionPixelSize(R.dimen.gutter) - Ui.dp(context, 5)
            setPadding(side, Ui.dp(context, 4), side, Ui.dp(context, 24))
            onEdit = { editComponent(it) }
            onReorder = { list -> save { it.copy(components = list) } }
        }
        page.body.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        Ui.headerIcon(page.header.root, R.drawable.ic_pencil_simple, getString(R.string.rename)) { rename() }

        page.footer.isVisible = true
        val add = Ui.button(page.footer, Ui.ButtonKind.PRIMARY, getString(R.string.panel_add_control), R.drawable.ic_plus) { addComponent() }
        page.footer.addView(add, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val done = Ui.button(page.footer, Ui.ButtonKind.SECONDARY, getString(R.string.done)) { finish() }
        page.footer.addView(done, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.gap)
        })
    }

    override fun render() {
        if (!::grid.isInitialized) return
        grid.submit(panel()?.components.orEmpty())
    }

    private fun save(update: (Panel) -> Panel) {
        val current = panel() ?: return
        store.save(update(current))
        refresh()
    }

    private fun update(component: PanelComponent) = save { p ->
        p.copy(components = p.components.map { if (it.id == component.id) component else it })
    }

    private fun rename() {
        val current = panel() ?: return
        Sheets.input(this, getString(R.string.rename), getString(R.string.panel_name), current.name) { name ->
            save { it.copy(name = name) }
        }
    }

    // ---- Adding ---------------------------------------------------------------

    private fun addComponent() {
        val choices = ComponentType.entries.map {
            Sheets.Choice(it, getString(it.labelRes), getString(describe(it)), icon(it))
        }
        Sheets.choose(this, getString(R.string.panel_add_control), choices, null,
            subtitle = getString(R.string.panel_add_body)) { type ->
            val component = PanelComponent(type = type, action = defaultAction(type))
            save { it.copy(components = it.components + component) }
            // Controls that do nothing until told what to do ask straight away.
            if (type in NEEDS_ACTION) pickAction(component, primary = true) { update(it) }
        }
    }

    private fun defaultAction(type: ComponentType): Action? = when (type) {
        ComponentType.TOGGLE -> Action.Consumer(HidReports.CC_MUTE)
        else -> null
    }

    // ---- Editing ----------------------------------------------------------------

    private fun editComponent(original: PanelComponent) {
        var c = original
        val sheet = Sheet(this).title(getString(c.type.labelRes)).subtitle(getString(describe(c.type)))

        val label = Ui.field(sheet.content, getString(R.string.panel_label), c.label,
            hint = grid.label(c.copy(label = null)))
        label.help(getString(R.string.panel_label_help))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet.content.addView(actions)

        fun actionRows() {
            actions.removeAllViews()
            when (c.type) {
                ComponentType.BUTTON, ComponentType.SHORTCUT, ComponentType.MACRO -> {
                    Ui.space(actions, 8)
                    Ui.valueRow(actions, getString(R.string.panel_does),
                        c.action?.let { ActionPicker.describe(this, it) } ?: getString(R.string.panel_pick)) {
                        pickAction(c, primary = true) { picked -> c = picked; actionRows() }
                    }.first.root.layoutParams = negativeMargins()
                }
                ComponentType.TOGGLE -> {
                    Ui.space(actions, 8)
                    val on = Action.fromJson(c.config.optJSONObject(PanelGrid.CONFIG_ON)) ?: c.action
                    val off = Action.fromJson(c.config.optJSONObject(PanelGrid.CONFIG_OFF)) ?: on
                    Ui.valueRow(actions, getString(R.string.panel_toggle_on), on?.let { ActionPicker.describe(this, it) } ?: getString(R.string.panel_pick)) {
                        pickAction(c, primary = true) { picked -> c = picked; actionRows() }
                    }.first.root.layoutParams = negativeMargins()
                    Ui.valueRow(actions, getString(R.string.panel_toggle_off), off?.let { ActionPicker.describe(this, it) } ?: getString(R.string.panel_pick)) {
                        pickAction(c, primary = false) { picked -> c = picked; actionRows() }
                    }.first.root.layoutParams = negativeMargins()
                }
                ComponentType.SLIDER -> {
                    Ui.space(actions, 8)
                    val up = Action.fromJson(c.config.optJSONObject(PanelGrid.CONFIG_UP)) ?: Action.Consumer(HidReports.CC_VOLUME_UP)
                    val down = Action.fromJson(c.config.optJSONObject(PanelGrid.CONFIG_DOWN)) ?: Action.Consumer(HidReports.CC_VOLUME_DOWN)
                    Ui.valueRow(actions, getString(R.string.panel_slider_up), ActionPicker.describe(this, up)) {
                        pickAction(c, primary = true) { picked -> c = picked; actionRows() }
                    }.first.root.layoutParams = negativeMargins()
                    Ui.valueRow(actions, getString(R.string.panel_slider_down), ActionPicker.describe(this, down)) {
                        pickAction(c, primary = false) { picked -> c = picked; actionRows() }
                    }.first.root.layoutParams = negativeMargins()
                }
                else -> Unit
            }
        }
        actionRows()

        // Size: columns across the 4-column grid, and rows down.
        fun sizeRow(title: Int, values: List<Int>, current: Int, onPick: (Int) -> Unit) {
            sheet.content.addView(android.widget.TextView(this).apply {
                setTextAppearance(R.style.Text_Label_Small)
                text = getString(title)
                setPadding(Ui.dp(context, 4), Ui.dp(context, 18), 0, Ui.dp(context, 8))
            })
            Ui.chipGroup(sheet.content, values.map { it to it.toString() }, current, onPick)
        }
        val minSpan = if (c.type in WIDE) 2 else 1
        sizeRow(R.string.panel_width, (minSpan..Panel.COLUMNS).toList(), c.span) { c = c.copy(span = it) }
        sizeRow(R.string.panel_height, (1..4).toList(), c.rows) { c = c.copy(rows = it) }

        // Order.
        val order = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(context, 20), 0, 0)
        }
        val earlier = Ui.button(order, Ui.ButtonKind.SMALL, getString(R.string.panel_move_earlier), R.drawable.ic_caret_left) {
            move(original.id, -1)
            sheet.dismiss()
        }
        val later = Ui.button(order, Ui.ButtonKind.SMALL, getString(R.string.panel_move_later), R.drawable.ic_caret_right) {
            move(original.id, 1)
            sheet.dismiss()
        }
        order.addView(earlier, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        order.addView(later, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(this@PanelEditorActivity, 10)
        })
        sheet.content.addView(order)

        sheet.secondary(getString(R.string.delete)) {
            save { p -> p.copy(components = p.components.filterNot { it.id == original.id }) }
        }
        sheet.primary(getString(R.string.done)) {
            update(c.copy(label = label.text.trim().ifEmpty { null }))
            true
        }
        sheet.show()
    }

    private fun negativeMargins() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = -Ui.dp(this@PanelEditorActivity, 8)
        marginEnd = -Ui.dp(this@PanelEditorActivity, 8)
    }

    private fun move(id: String, by: Int) = save { p ->
        val list = p.components.toMutableList()
        val at = list.indexOfFirst { it.id == id }
        val to = (at + by).coerceIn(0, list.size - 1)
        if (at < 0 || at == to) p else p.copy(components = list.apply { add(to, removeAt(at)) })
    }

    /**
     * Picks an action for a component and returns the updated copy:
     * [primary] is the main action (or "on" / "up"), otherwise "off" / "down".
     */
    private fun pickAction(c: PanelComponent, primary: Boolean, done: (PanelComponent) -> Unit) {
        when (c.type) {
            ComponentType.MACRO -> {
                val macros = MacroStore(this).all()
                if (macros.isEmpty()) {
                    Sheets.confirm(this, getString(R.string.panel_no_macros), getString(R.string.panel_no_macros_body),
                        getString(R.string.macro_new)) { Library.newMacro(libraryHost) }
                    return
                }
                Sheets.choose(this, getString(R.string.comp_macro),
                    macros.map { Sheets.Choice(it.id, it.name, resources.getQuantityString(R.plurals.macro_steps, it.steps.size, it.steps.size), R.drawable.ic_magic_wand) },
                    (c.action as? Action.RunMacro)?.macroId) { id ->
                    done(c.copy(action = Action.RunMacro(id)))
                }
            }
            ComponentType.SHORTCUT -> ActionPicker.show(this, getString(R.string.comp_shortcut),
                setOf(Actions.Group.SHORTCUTS, Actions.Group.KEYBOARD), current = c.action) { done(c.copy(action = it.action)) }
            ComponentType.SLIDER -> {
                val key = if (primary) PanelGrid.CONFIG_UP else PanelGrid.CONFIG_DOWN
                ActionPicker.show(this, getString(if (primary) R.string.panel_slider_up else R.string.panel_slider_down),
                    setOf(Actions.Group.MEDIA, Actions.Group.TV, Actions.Group.NAVIGATION, Actions.Group.MOUSE, Actions.Group.SHORTCUTS),
                    current = Action.fromJson(c.config.optJSONObject(key))) { picked ->
                    done(c.copy(config = org.json.JSONObject(c.config.toString()).put(key, picked.action.toJson())))
                }
            }
            ComponentType.TOGGLE -> {
                val key = if (primary) PanelGrid.CONFIG_ON else PanelGrid.CONFIG_OFF
                ActionPicker.show(this, getString(if (primary) R.string.panel_toggle_on else R.string.panel_toggle_off),
                    includeMacros = true, current = Action.fromJson(c.config.optJSONObject(key))) { picked ->
                    val config = org.json.JSONObject(c.config.toString()).put(key, picked.action.toJson())
                    done(if (primary) c.copy(action = picked.action, config = config) else c.copy(config = config))
                }
            }
            else -> ActionPicker.show(this, getString(R.string.pick_action), includeMacros = true, current = c.action) {
                done(c.copy(action = it.action))
            }
        }
    }

    private val libraryHost = object : Library.Host {
        override val hostActivity get() = this@PanelEditorActivity
        override fun refresh() = this@PanelEditorActivity.refresh()
        override fun openPanel(id: String) = Unit
        override fun runMacro(macro: Macro) = Unit
    }

    private fun describe(type: ComponentType): Int = when (type) {
        ComponentType.BUTTON -> R.string.comp_button_body
        ComponentType.DPAD -> R.string.comp_dpad_body
        ComponentType.TRACKPAD -> R.string.comp_trackpad_body
        ComponentType.SLIDER -> R.string.comp_slider_body
        ComponentType.TOGGLE -> R.string.comp_toggle_body
        ComponentType.KEYBOARD -> R.string.comp_keyboard_body
        ComponentType.MEDIA -> R.string.comp_media_body
        ComponentType.TEXT_INPUT -> R.string.comp_text_input_body
        ComponentType.SHORTCUT -> R.string.comp_shortcut_body
        ComponentType.MACRO -> R.string.comp_macro_body
    }

    private fun icon(type: ComponentType): Int = when (type) {
        ComponentType.BUTTON -> R.drawable.ic_circle_fill
        ComponentType.DPAD -> R.drawable.ic_arrows_out_cardinal
        ComponentType.TRACKPAD -> R.drawable.ic_cursor
        ComponentType.SLIDER -> R.drawable.ic_sliders_horizontal
        ComponentType.TOGGLE -> R.drawable.ic_toggle_right
        ComponentType.KEYBOARD -> R.drawable.ic_keyboard
        ComponentType.MEDIA -> R.drawable.ic_play_pause
        ComponentType.TEXT_INPUT -> R.drawable.ic_text_t
        ComponentType.SHORTCUT -> R.drawable.ic_command
        ComponentType.MACRO -> R.drawable.ic_magic_wand
    }

    companion object {
        const val EXTRA_PANEL = "panel"
        private val NEEDS_ACTION = setOf(ComponentType.BUTTON, ComponentType.SHORTCUT, ComponentType.MACRO)
        private val WIDE = setOf(ComponentType.DPAD, ComponentType.TRACKPAD, ComponentType.MEDIA, ComponentType.SLIDER)

        fun open(context: Context, panelId: String) {
            context.startActivity(Intent(context, PanelEditorActivity::class.java).putExtra(EXTRA_PANEL, panelId))
        }
    }
}
