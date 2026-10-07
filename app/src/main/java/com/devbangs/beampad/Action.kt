package com.devbangs.beampad

import org.json.JSONObject

/**
 * Anything a control can make the connected device do. Panels, macros,
 * gestures and remote buttons all store one of these, so they share one
 * executor and one vocabulary.
 */
sealed interface Action {

    /** A keyboard key by HID usage, with modifier bits held (see HidReports.MOD_*). */
    data class Key(val usage: Int, val modifiers: Int = 0) : Action

    /** A consumer-control usage: media, volume, power, channel, AC Home/Back/Search. */
    data class Consumer(val usage: Int) : Action

    /** Types a string with the current keyboard layout. */
    data class Text(val text: String) : Action

    /** Clicks a mouse button (HidReports.BUTTON_*), once or twice. */
    data class Click(val button: Int, val double: Boolean = false) : Action

    /** Wheel notches; positive scrolls up. */
    data class Scroll(val amount: Int) : Action

    /** Pause between macro steps, for devices that need time to react. */
    data class Delay(val millis: Long) : Action

    /** Runs a saved macro by id. */
    data class RunMacro(val macroId: String) : Action

    fun toJson(): JSONObject = when (this) {
        is Key -> JSONObject().put("t", "key").put("u", usage).put("m", modifiers)
        is Consumer -> JSONObject().put("t", "cc").put("u", usage)
        is Text -> JSONObject().put("t", "text").put("s", text)
        is Click -> JSONObject().put("t", "click").put("b", button).put("d", double)
        is Scroll -> JSONObject().put("t", "scroll").put("a", amount)
        is Delay -> JSONObject().put("t", "delay").put("ms", millis)
        is RunMacro -> JSONObject().put("t", "macro").put("id", macroId)
    }

    companion object {
        /** Null for anything malformed or from a newer version, never a crash. */
        fun fromJson(o: JSONObject?): Action? = runCatching {
            when (o?.optString("t")) {
                "key" -> Key(o.getInt("u"), o.optInt("m", 0))
                "cc" -> Consumer(o.getInt("u"))
                "text" -> Text(o.getString("s"))
                "click" -> Click(o.getInt("b"), o.optBoolean("d", false))
                "scroll" -> Scroll(o.getInt("a"))
                "delay" -> Delay(o.getLong("ms"))
                "macro" -> RunMacro(o.getString("id"))
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * The named actions users pick from in panels, macros and gesture settings.
 * Ids are stable and stored; labels are resources so they translate.
 */
object Actions {

    enum class Group(val labelRes: Int) {
        NAVIGATION(R.string.group_navigation),
        MEDIA(R.string.group_media),
        TV(R.string.group_tv),
        KEYBOARD(R.string.group_keyboard),
        SHORTCUTS(R.string.group_shortcuts),
        MOUSE(R.string.group_mouse),
        PRESENTATION(R.string.group_presentation)
    }

    data class Named(
        val id: String,
        val labelRes: Int,
        val iconRes: Int?,
        val group: Group,
        val action: Action
    )

    private fun key(usage: Int, mods: Int = 0) = Action.Key(usage, mods)
    private fun cc(usage: Int) = Action.Consumer(usage)

    private const val CTRL = HidReports.MOD_LEFT_CTRL.toInt()
    private const val SHIFT = HidReports.MOD_LEFT_SHIFT.toInt()
    private const val ALT = HidReports.MOD_LEFT_ALT.toInt()
    private const val META = HidReports.MOD_LEFT_META.toInt()

    val all: List<Named> = buildList {
        val g = Group.NAVIGATION
        add(Named("up", R.string.act_up, R.drawable.ic_arrow_up, g, key(HidReports.KEY_UP.toInt())))
        add(Named("down", R.string.act_down, R.drawable.ic_arrow_down, g, key(HidReports.KEY_DOWN.toInt())))
        add(Named("left", R.string.act_left, R.drawable.ic_arrow_left, g, key(HidReports.KEY_LEFT.toInt())))
        add(Named("right", R.string.act_right, R.drawable.ic_arrow_right, g, key(HidReports.KEY_RIGHT.toInt())))
        add(Named("ok", R.string.act_ok, R.drawable.ic_circle_fill, g, key(HidReports.KEY_ENTER.toInt())))
        add(Named("back", R.string.act_back, R.drawable.ic_arrow_u_up_left, g, key(HidReports.KEY_ESC.toInt())))
        add(Named("home", R.string.act_home, R.drawable.ic_house, g, cc(HidReports.CC_HOME)))
        add(Named("menu", R.string.act_menu, R.drawable.ic_list, g, cc(HidReports.CC_MENU)))
        add(Named("search", R.string.act_search, R.drawable.ic_magnifying_glass, g, cc(HidReports.CC_SEARCH)))
        add(Named("browser_back", R.string.act_browser_back, null, g, cc(HidReports.CC_BACK)))
        add(Named("browser_forward", R.string.act_browser_forward, null, g, cc(HidReports.CC_FORWARD)))
    } + buildList {
        val g = Group.MEDIA
        add(Named("play_pause", R.string.act_play_pause, R.drawable.ic_play_pause, g, cc(HidReports.CC_PLAY_PAUSE)))
        add(Named("next_track", R.string.act_next_track, R.drawable.ic_skip_forward, g, cc(HidReports.CC_SCAN_NEXT)))
        add(Named("prev_track", R.string.act_prev_track, R.drawable.ic_skip_back, g, cc(HidReports.CC_SCAN_PREV)))
        add(Named("seek_forward", R.string.act_seek_forward, R.drawable.ic_fast_forward, g, cc(HidReports.CC_FAST_FORWARD)))
        add(Named("seek_back", R.string.act_seek_back, R.drawable.ic_rewind, g, cc(HidReports.CC_REWIND)))
        add(Named("stop", R.string.act_stop, R.drawable.ic_stop, g, cc(HidReports.CC_STOP)))
        add(Named("vol_up", R.string.act_vol_up, R.drawable.ic_plus, g, cc(HidReports.CC_VOLUME_UP)))
        add(Named("vol_down", R.string.act_vol_down, R.drawable.ic_minus, g, cc(HidReports.CC_VOLUME_DOWN)))
        add(Named("mute", R.string.act_mute, R.drawable.ic_speaker_slash, g, cc(HidReports.CC_MUTE)))
        add(Named("subtitles", R.string.act_subtitles, R.drawable.ic_subtitles, g, cc(HidReports.CC_CLOSED_CAPTION)))
        // Web and desktop players: F toggles fullscreen, Shift+. and Shift+,
        // change speed (YouTube, VLC-style). Shown as "where supported".
        add(Named("fullscreen", R.string.act_fullscreen, R.drawable.ic_corners_out, g, key(HidReports.KEY_F.toInt())))
        add(Named("speed_up", R.string.act_speed_up, null, g, key(HidReports.KEY_PERIOD.toInt(), SHIFT)))
        add(Named("speed_down", R.string.act_speed_down, null, g, key(HidReports.KEY_COMMA.toInt(), SHIFT)))
    } + buildList {
        val g = Group.TV
        add(Named("power", R.string.act_power, R.drawable.ic_power, g, cc(HidReports.CC_POWER)))
        add(Named("sleep", R.string.act_sleep, R.drawable.ic_moon, g, cc(HidReports.CC_SLEEP)))
        add(Named("channel_up", R.string.act_channel_up, R.drawable.ic_caret_double_up, g, cc(HidReports.CC_CHANNEL_UP)))
        add(Named("channel_down", R.string.act_channel_down, R.drawable.ic_caret_double_down, g, cc(HidReports.CC_CHANNEL_DOWN)))
    } + buildList {
        val g = Group.KEYBOARD
        add(Named("enter", R.string.act_enter, R.drawable.ic_arrow_elbow_down_left, g, key(HidReports.KEY_ENTER.toInt())))
        add(Named("esc", R.string.act_esc, null, g, key(HidReports.KEY_ESC.toInt())))
        add(Named("tab", R.string.act_tab, null, g, key(HidReports.KEY_TAB.toInt())))
        add(Named("space", R.string.act_space, null, g, key(HidReports.KEY_SPACE.toInt())))
        add(Named("backspace", R.string.act_backspace, R.drawable.ic_backspace, g, key(HidReports.KEY_BACKSPACE.toInt())))
        add(Named("delete", R.string.act_delete, null, g, key(HidReports.KEY_DELETE.toInt())))
        add(Named("line_start", R.string.act_line_start, null, g, key(HidReports.KEY_HOME.toInt())))
        add(Named("line_end", R.string.act_line_end, null, g, key(HidReports.KEY_END.toInt())))
        add(Named("page_up", R.string.act_page_up, null, g, key(HidReports.KEY_PAGE_UP.toInt())))
        add(Named("page_down", R.string.act_page_down, null, g, key(HidReports.KEY_PAGE_DOWN.toInt())))
        for (n in 1..12) {
            add(Named("f$n", R.string.act_f_template, null, g, key(HidReports.functionKey(n).toInt())))
        }
        add(Named("print_screen", R.string.act_print_screen, null, g, key(0x46)))
        add(Named("insert", R.string.act_insert, null, g, key(0x49)))
        add(Named("caps_lock", R.string.act_caps_lock, null, g, key(0x39)))
        add(Named("context_menu", R.string.act_context_menu, null, g, key(0x65)))
    } + buildList {
        val g = Group.SHORTCUTS
        add(Named("select_all", R.string.act_select_all, null, g, key(HidReports.KEY_A.toInt(), CTRL)))
        add(Named("copy", R.string.act_copy, R.drawable.ic_copy_simple, g, key(HidReports.KEY_C.toInt(), CTRL)))
        add(Named("paste", R.string.act_paste, R.drawable.ic_clipboard_text, g, key(HidReports.KEY_V.toInt(), CTRL)))
        add(Named("cut", R.string.act_cut, null, g, key(HidReports.KEY_X.toInt(), CTRL)))
        add(Named("undo", R.string.act_undo, R.drawable.ic_arrow_counter_clockwise, g, key(HidReports.KEY_Z.toInt(), CTRL)))
        add(Named("app_switch", R.string.act_app_switch, null, g, key(HidReports.KEY_TAB.toInt(), ALT)))
        add(Named("close_window", R.string.act_close_window, null, g, key(HidReports.KEY_F4.toInt(), ALT)))
        add(Named("show_desktop", R.string.act_show_desktop, null, g, key(HidReports.KEY_D.toInt(), META)))
        // The Meta key is a modifier bit, so it is sent alone with no key.
        add(Named("start_menu", R.string.act_start_menu, null, g, key(0, META)))
        add(Named("zoom_in", R.string.act_zoom_in, R.drawable.ic_magnifying_glass_plus, g, key(HidReports.KEY_EQUALS.toInt(), CTRL)))
        add(Named("zoom_out", R.string.act_zoom_out, R.drawable.ic_magnifying_glass_minus, g, key(HidReports.KEY_MINUS.toInt(), CTRL)))
        add(Named("redo", R.string.act_redo, R.drawable.ic_arrow_clockwise, g, key(0x1C, CTRL)))
        add(Named("find", R.string.act_find, R.drawable.ic_magnifying_glass, g, key(HidReports.KEY_F.toInt(), CTRL)))
        add(Named("save", R.string.act_save, null, g, key(0x16, CTRL)))
        add(Named("new_tab", R.string.act_new_tab, null, g, key(0x17, CTRL)))
        add(Named("close_tab", R.string.act_close_tab, null, g, key(0x1A, CTRL)))
        add(Named("refresh", R.string.act_refresh, R.drawable.ic_arrow_clockwise, g, key(HidReports.KEY_F5.toInt())))
        add(Named("task_view", R.string.act_task_view, null, g, key(HidReports.KEY_TAB.toInt(), META)))
        add(Named("file_explorer", R.string.act_file_explorer, null, g, key(0x08, META)))
        add(Named("lock_pc", R.string.act_lock_pc, R.drawable.ic_lock_simple, g, key(0x0F, META)))
        add(Named("task_manager", R.string.act_task_manager, null, g, key(HidReports.KEY_ESC.toInt(), CTRL or SHIFT)))
        add(Named("screenshot", R.string.act_screenshot, null, g, key(0x16, META or SHIFT)))
    } + buildList {
        val g = Group.MOUSE
        add(Named("left_click", R.string.act_left_click, R.drawable.ic_mouse_left_click, g, Action.Click(HidReports.BUTTON_LEFT.toInt())))
        add(Named("right_click", R.string.act_right_click, R.drawable.ic_mouse_right_click, g, Action.Click(HidReports.BUTTON_RIGHT.toInt())))
        add(Named("middle_click", R.string.act_middle_click, null, g, Action.Click(HidReports.BUTTON_MIDDLE.toInt())))
        add(Named("double_click", R.string.act_double_click, null, g, Action.Click(HidReports.BUTTON_LEFT.toInt(), double = true)))
        add(Named("scroll_up", R.string.act_scroll_up, null, g, Action.Scroll(3)))
        add(Named("scroll_down", R.string.act_scroll_down, null, g, Action.Scroll(-3)))
    } + buildList {
        val g = Group.PRESENTATION
        add(Named("slide_next", R.string.act_slide_next, R.drawable.ic_caret_right, g, key(HidReports.KEY_PAGE_DOWN.toInt())))
        add(Named("slide_prev", R.string.act_slide_prev, R.drawable.ic_caret_left, g, key(HidReports.KEY_PAGE_UP.toInt())))
        add(Named("slideshow_start", R.string.act_slideshow_start, null, g, key(HidReports.KEY_F5.toInt())))
        add(Named("slideshow_end", R.string.act_slideshow_end, null, g, key(HidReports.KEY_ESC.toInt())))
        add(Named("black_screen", R.string.act_black_screen, null, g, key(HidReports.KEY_B.toInt())))
    }

    private val byId = all.associateBy { it.id }

    fun byId(id: String?): Named? = id?.let { byId[it] }

    /** Display label; function keys share one template. */
    fun label(context: android.content.Context, named: Named): String =
        if (named.labelRes == R.string.act_f_template) {
            context.getString(R.string.act_f_template, named.id.drop(1).toInt())
        } else {
            context.getString(named.labelRes)
        }

    /** The catalogue entry an action came from, if any, for showing its name. */
    fun find(action: Action): Named? = all.firstOrNull { it.action == action }
}
