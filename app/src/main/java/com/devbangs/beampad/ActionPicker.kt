package com.devbangs.beampad

import android.content.Context
import android.widget.TextView

/**
 * "What should this do?" — one picker for panel buttons, macro steps and
 * gesture settings, grouped like a remote's sections. Shared so every place
 * that assigns an action offers the same list with the same names.
 */
object ActionPicker {

    /**
     * Shows the catalogue, optionally limited to [groups], and reports the
     * chosen entry. [includeMacros] adds the user's saved macros at the end;
     * [excludeMacro] keeps a macro from being offered inside itself.
     */
    fun show(
        context: Context,
        title: CharSequence,
        groups: Set<Actions.Group> = Actions.Group.entries.toSet(),
        includeMacros: Boolean = false,
        excludeMacro: String? = null,
        current: Action? = null,
        onPicked: (Choice) -> Unit
    ) {
        val sheet = Sheet(context).title(title)

        fun header(text: String) {
            sheet.content.addView(TextView(context).apply {
                setTextAppearance(R.style.Text_Overline)
                this.text = text
                setPadding(0, Ui.dp(context, 18), 0, Ui.dp(context, 4))
            })
        }

        Actions.Group.entries.filter { it in groups }.forEach { group ->
            val entries = Actions.all.filter { it.group == group }
            if (entries.isEmpty()) return@forEach
            header(context.getString(group.labelRes))
            entries.forEach { named ->
                sheet.option(Actions.label(context, named), null, named.iconRes ?: groupIcon(group), named.action == current) {
                    onPicked(Choice(named.action, named))
                }
            }
        }

        if (includeMacros) {
            val macros = MacroStore(context).all().filter { it.id != excludeMacro }
            if (macros.isNotEmpty()) {
                header(context.getString(R.string.group_macros))
                macros.forEach { macro ->
                    val action = Action.RunMacro(macro.id)
                    sheet.option(macro.name, context.resources.getQuantityString(R.plurals.macro_steps, macro.steps.size, macro.steps.size),
                        R.drawable.ic_magic_wand, action == current) {
                        onPicked(Choice(action, null, macro))
                    }
                }
            }
        }
        sheet.show()
    }

    private fun groupIcon(group: Actions.Group): Int = when (group) {
        Actions.Group.NAVIGATION -> R.drawable.ic_arrows_out_cardinal
        Actions.Group.MEDIA -> R.drawable.ic_play_pause
        Actions.Group.TV -> R.drawable.ic_television_simple
        Actions.Group.KEYBOARD -> R.drawable.ic_keyboard
        Actions.Group.SHORTCUTS -> R.drawable.ic_command
        Actions.Group.MOUSE -> R.drawable.ic_mouse_simple
        Actions.Group.PRESENTATION -> R.drawable.ic_presentation
    }

    /** What was picked: the action, plus where it came from for naming it. */
    data class Choice(val action: Action, val named: Actions.Named?, val macro: Macro? = null) {
        fun label(context: Context): String = when {
            named != null -> Actions.label(context, named)
            macro != null -> macro.name
            else -> describe(context, action)
        }
    }

    /** A readable name for any action, catalogued or not. */
    fun describe(context: Context, action: Action): String {
        Actions.find(action)?.let { return Actions.label(context, it) }
        return when (action) {
            is Action.Text -> context.getString(R.string.act_type_text, action.text.take(24))
            is Action.Delay -> context.getString(R.string.act_wait_ms, action.millis)
            is Action.RunMacro -> MacroStore(context).get(action.macroId)?.name
                ?: context.getString(R.string.act_missing_macro)
            is Action.Key -> context.getString(R.string.act_key_code, action.usage)
            is Action.Consumer -> context.getString(R.string.act_media_code, action.usage)
            is Action.Click -> context.getString(R.string.act_left_click)
            is Action.Scroll -> context.getString(
                if (action.amount > 0) R.string.act_scroll_up else R.string.act_scroll_down
            )
        }
    }

    /** An icon for any action: its catalogue icon, else its group's. */
    fun icon(action: Action?): Int {
        if (action == null) return R.drawable.ic_plus
        val named = Actions.find(action)
        if (named != null) return named.iconRes ?: groupIcon(named.group)
        return when (action) {
            is Action.Text -> R.drawable.ic_text_t
            is Action.Delay -> R.drawable.ic_timer
            is Action.RunMacro -> R.drawable.ic_magic_wand
            is Action.Key -> R.drawable.ic_keyboard
            is Action.Consumer -> R.drawable.ic_play_pause
            is Action.Click, is Action.Scroll -> R.drawable.ic_mouse_simple
        }
    }
}
