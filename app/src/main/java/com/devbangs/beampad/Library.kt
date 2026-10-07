package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import android.view.ViewGroup

/**
 * Panels and macros as lists, plus the templates and examples users start
 * from. Shared by the Panels tab and the Macros page so both draw the same
 * rows with the same actions.
 */
object Library {

    // ---- Templates ------------------------------------------------------------

    enum class PanelTemplate(val title: Int, val body: Int, val icon: Int) {
        TV(R.string.tpl_tv, R.string.tpl_tv_body, R.drawable.ic_television_simple),
        PRESENTATION(R.string.tpl_present, R.string.tpl_present_body, R.drawable.ic_presentation),
        MEDIA(R.string.tpl_media, R.string.tpl_media_body, R.drawable.ic_play_pause),
        DESKTOP(R.string.tpl_desktop, R.string.tpl_desktop_body, R.drawable.ic_desktop)
    }

    private fun button(id: String, span: Int = 1, rows: Int = 1, label: String? = null): PanelComponent? =
        Actions.byId(id)?.let { PanelComponent(type = ComponentType.BUTTON, action = it.action, span = span, rows = rows, label = label) }

    private fun of(type: ComponentType, span: Int = type.defaultSpan, rows: Int = type.defaultRows, label: String? = null) =
        PanelComponent(type = type, span = span, rows = rows, label = label)

    /**
     * A button that runs one of the example macros. The macro is saved the
     * first time a template needs it and reused after that, so it can be
     * edited like any other.
     */
    private fun exampleMacro(context: Context, example: MacroExample, label: Int, span: Int): PanelComponent {
        val store = MacroStore(context)
        val name = context.getString(example.title)
        val macro = store.all().firstOrNull { it.name == name }
            ?: Macro(name = name, steps = steps(example)).also { store.save(it) }
        return PanelComponent(
            type = ComponentType.MACRO,
            action = Action.RunMacro(macro.id),
            span = span,
            label = context.getString(label)
        )
    }

    fun components(context: Context, template: PanelTemplate): List<PanelComponent> = when (template) {
        // The blueprint's "My TV": YouTube, Netflix, Trackpad, Home, Back,
        // volume and keyboard, with the D-pad and media a TV also needs.
        PanelTemplate.TV -> listOfNotNull(
            exampleMacro(context, MacroExample.YOUTUBE, R.string.ex_youtube_short, span = 2),
            exampleMacro(context, MacroExample.NETFLIX, R.string.ex_netflix_short, span = 2),
            button("home"), button("back"), button("menu"), button("search"),
            of(ComponentType.DPAD),
            of(ComponentType.TRACKPAD, rows = 2),
            of(ComponentType.SLIDER),
            of(ComponentType.MEDIA),
            of(ComponentType.KEYBOARD)
        )
        PanelTemplate.PRESENTATION -> listOfNotNull(
            button("slide_prev", span = 2, rows = 2), button("slide_next", span = 2, rows = 2),
            of(ComponentType.TRACKPAD, rows = 2, label = context.getString(R.string.tpl_pointer)),
            button("slideshow_start"), button("black_screen"), button("slideshow_end"), button("left_click")
        )
        PanelTemplate.MEDIA -> listOfNotNull(
            of(ComponentType.MEDIA),
            button("seek_back", span = 2), button("seek_forward", span = 2),
            of(ComponentType.SLIDER),
            Actions.byId("mute")?.let { PanelComponent(type = ComponentType.TOGGLE, action = it.action, span = 2) },
            button("subtitles", span = 2),
            button("fullscreen", span = 2), button("speed_down"), button("speed_up")
        )
        PanelTemplate.DESKTOP -> listOfNotNull(
            of(ComponentType.TRACKPAD),
            button("copy"), button("paste"), button("undo"), button("select_all"),
            button("app_switch", span = 2), button("show_desktop", span = 2),
            of(ComponentType.TEXT_INPUT)
        )
    }

    enum class MacroExample(val title: Int, val body: Int) {
        YOUTUBE(R.string.ex_youtube, R.string.ex_youtube_body),
        NETFLIX(R.string.ex_netflix, R.string.ex_netflix_body),
        MOVIE_NIGHT(R.string.ex_movie_night, R.string.ex_movie_night_body),
        QUIET(R.string.ex_quiet, R.string.ex_quiet_body)
    }

    /** Home, then the TV's search, then [app] typed and opened, as the launcher does. */
    private fun openApp(app: String): List<Action> = Launcher.steps(TvApp(app.lowercase(), app))

    fun steps(example: MacroExample): List<Action> = when (example) {
        MacroExample.YOUTUBE -> openApp("YouTube")
        MacroExample.NETFLIX -> openApp("Netflix")
        // The blueprint's Movie Night: Home, open the media app, pick the
        // profile, play the highlighted title, then set the volume.
        MacroExample.MOVIE_NIGHT -> buildList {
            addAll(openApp("Netflix"))
            add(Action.Delay(6000))
            add(Action.Key(HidReports.KEY_ENTER.toInt()))
            add(Action.Delay(3000))
            add(Action.Key(HidReports.KEY_ENTER.toInt()))
            add(Action.Delay(2500))
            add(Action.Consumer(HidReports.CC_PLAY_PAUSE))
            repeat(3) {
                add(Action.Delay(150))
                add(Action.Consumer(HidReports.CC_VOLUME_UP))
            }
        }
        MacroExample.QUIET -> buildList {
            repeat(5) {
                add(Action.Consumer(HidReports.CC_VOLUME_DOWN))
                add(Action.Delay(150))
            }
        }
    }

    // ---- Lists ----------------------------------------------------------------

    /** Callbacks the lists need from their screen. */
    interface Host {
        val hostActivity: Activity
        fun refresh()
        fun openPanel(id: String)
        fun runMacro(macro: Macro)
    }

    private fun requirePro(context: Context, feature: Features.Pro): Boolean {
        if (Features.isPro(context)) return true
        ProActivity.open(context, feature)
        return false
    }

    fun newPanel(host: Host) {
        val c = host.hostActivity
        if (!requirePro(c, Features.Pro.PANELS)) return
        Sheets.input(c, c.getString(R.string.panel_new), c.getString(R.string.panel_name),
            hint = c.getString(R.string.panel_name_hint)) { name ->
            val panel = PanelStore(c).create(name)
            PanelEditorActivity.open(c, panel.id)
        }
    }

    fun fromTemplate(host: Host, template: PanelTemplate) {
        val c = host.hostActivity
        if (!requirePro(c, Features.Pro.PANELS)) return
        val panel = Panel(name = c.getString(template.title), components = components(c, template))
        PanelStore(c).save(panel)
        PanelEditorActivity.open(c, panel.id)
    }

    fun newMacro(host: Host) {
        val c = host.hostActivity
        if (!requirePro(c, Features.Pro.MACROS)) return
        Sheets.input(c, c.getString(R.string.macro_new), c.getString(R.string.macro_name),
            hint = c.getString(R.string.macro_name_hint)) { name ->
            val macro = Macro(name = name)
            MacroStore(c).save(macro)
            MacroEditorActivity.open(c, macro.id)
        }
    }

    fun fromExample(host: Host, example: MacroExample) {
        val c = host.hostActivity
        if (!requirePro(c, Features.Pro.MACROS)) return
        val macro = Macro(name = c.getString(example.title), steps = steps(example))
        MacroStore(c).save(macro)
        MacroEditorActivity.open(c, macro.id)
    }

    fun renderPanels(parent: ViewGroup, host: Host) {
        val c = host.hostActivity
        val pro = Features.isPro(c)
        val store = PanelStore(c)
        val panels = store.all()

        if (!pro) upsell(parent, c, R.string.panels_upsell_title, R.string.panels_upsell_body, Features.Pro.PANELS)

        if (panels.isNotEmpty()) {
            Ui.section(parent, c.getString(R.string.panels_yours))
            val card = Ui.card(parent)
            panels.forEachIndexed { i, panel ->
                if (i > 0) Ui.divider(card)
                val row = Ui.row(card, panel.name,
                    c.resources.getQuantityString(R.plurals.panel_controls, panel.components.size, panel.components.size),
                    R.drawable.ic_layout) {
                    if (requirePro(c, Features.Pro.PANELS)) host.openPanel(panel.id)
                }
                row.trailing.addView(overflow(row.trailing, c) { panelActions(host, panel, i, panels.size) })
            }
        }

        Ui.section(parent, c.getString(R.string.panels_templates))
        val templates = Ui.card(parent)
        PanelTemplate.entries.forEachIndexed { i, t ->
            if (i > 0) Ui.divider(templates)
            Ui.linkRow(templates, c.getString(t.title), c.getString(t.body), t.icon) { fromTemplate(host, t) }
                .also { it.pro(!pro) }
        }
    }

    fun renderMacros(parent: ViewGroup, host: Host) {
        val c = host.hostActivity
        val pro = Features.isPro(c)
        val macros = MacroStore(c).all()

        if (!pro) upsell(parent, c, R.string.macros_upsell_title, R.string.macros_upsell_body, Features.Pro.MACROS)

        if (macros.isNotEmpty()) {
            Ui.section(parent, c.getString(R.string.macros_yours))
            val card = Ui.card(parent)
            macros.forEachIndexed { i, macro ->
                if (i > 0) Ui.divider(card)
                val row = Ui.row(card, macro.name,
                    c.resources.getQuantityString(R.plurals.macro_steps, macro.steps.size, macro.steps.size),
                    R.drawable.ic_magic_wand) {
                    if (requirePro(c, Features.Pro.MACROS)) MacroEditorActivity.open(c, macro.id)
                }
                row.trailing.addView(Ui.button(row.trailing, Ui.ButtonKind.SMALL, c.getString(R.string.macro_run), R.drawable.ic_play) {
                    if (requirePro(c, Features.Pro.MACROS)) host.runMacro(macro)
                })
            }
        }

        Ui.section(parent, c.getString(R.string.macros_examples))
        val examples = Ui.card(parent)
        MacroExample.entries.forEachIndexed { i, e ->
            if (i > 0) Ui.divider(examples)
            Ui.linkRow(examples, c.getString(e.title), c.getString(e.body), R.drawable.ic_sparkle_fill) { fromExample(host, e) }
                .also { it.pro(!pro) }
        }
    }

    private fun upsell(parent: ViewGroup, c: Context, title: Int, body: Int, feature: Features.Pro) {
        Ui.space(parent, 4)
        val card = Ui.card(parent)
        val row = Ui.row(card, c.getString(title), c.getString(body), R.drawable.ic_crown_simple_fill, Ui.Tone.ACCENT)
        row.subtitle.maxLines = 4
        row.trailing.addView(Ui.button(row.trailing, Ui.ButtonKind.SMALL_PRIMARY, c.getString(R.string.see_pro)) {
            ProActivity.open(c, feature)
        })
    }

    private fun overflow(parent: ViewGroup, c: Context, onClick: () -> Unit) =
        (android.view.LayoutInflater.from(c).inflate(R.layout.ui_icon_button, parent, false) as android.widget.ImageButton).apply {
            setImageResource(R.drawable.ic_dots_three)
            setBackgroundResource(R.drawable.bg_icon_button_plain)
            imageTintList = android.content.res.ColorStateList.valueOf(c.themeColor(R.attr.bpTextDim))
            contentDescription = c.getString(R.string.more_options)
            setOnClickListener { onClick() }
        }

    private fun panelActions(host: Host, panel: Panel, index: Int, count: Int) {
        val c = host.hostActivity
        val store = PanelStore(c)
        val sheet = Sheet(c).title(panel.name)
        sheet.option(c.getString(R.string.panel_open), icon = R.drawable.ic_play) {
            if (requirePro(c, Features.Pro.PANELS)) host.openPanel(panel.id)
        }
        sheet.option(c.getString(R.string.panel_edit), icon = R.drawable.ic_pencil_simple) {
            if (requirePro(c, Features.Pro.PANELS)) PanelEditorActivity.open(c, panel.id)
        }
        sheet.option(c.getString(R.string.rename), icon = R.drawable.ic_text_t) {
            Sheets.input(c, c.getString(R.string.rename), c.getString(R.string.panel_name), panel.name) { name ->
                store.save(panel.copy(name = name))
                host.refresh()
            }
        }
        sheet.option(c.getString(R.string.duplicate), icon = R.drawable.ic_copy_simple) {
            if (requirePro(c, Features.Pro.PANELS)) {
                store.duplicate(panel.id, c.getString(R.string.panel_copy_name, panel.name))
                host.refresh()
            }
        }
        if (index > 0) sheet.option(c.getString(R.string.move_up), icon = R.drawable.ic_caret_up) {
            val ids = store.all().map { it.id }.toMutableList()
            ids.add(index - 1, ids.removeAt(index))
            store.reorder(ids)
            host.refresh()
        }
        if (index < count - 1) sheet.option(c.getString(R.string.move_down), icon = R.drawable.ic_caret_down) {
            val ids = store.all().map { it.id }.toMutableList()
            ids.add(index + 1, ids.removeAt(index))
            store.reorder(ids)
            host.refresh()
        }
        sheet.option(c.getString(R.string.delete), icon = R.drawable.ic_trash) {
            Sheets.confirm(c, c.getString(R.string.panel_delete_confirm, panel.name),
                c.getString(R.string.panel_delete_body), c.getString(R.string.delete), destructive = true) {
                store.delete(panel.id)
                if (Prefs(c).lastPanelId == panel.id) Prefs(c).lastPanelId = null
                host.refresh()
            }
        }
        sheet.show()
    }
}
