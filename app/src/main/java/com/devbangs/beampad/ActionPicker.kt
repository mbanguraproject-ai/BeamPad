package com.devbangs.beampad

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.devbangs.beampad.databinding.ItemActionBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * "What should this do?" — one picker for panel buttons, macro steps and
 * gesture settings, grouped like a remote's sections. Shared so every place
 * that assigns an action offers the same list with the same names.
 */
object ActionPicker {

    /**
     * Shows the catalogue, optionally limited to [groups], and reports the
     * chosen entry. [includeMacros] adds the user's saved macros at the end.
     */
    fun show(
        context: Context,
        title: CharSequence,
        groups: Set<Actions.Group> = Actions.Group.entries.toSet(),
        includeMacros: Boolean = false,
        onPicked: (Choice) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val dialog = BottomSheetDialog(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(context).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(context.themeColor(R.attr.bpText))
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        })

        fun header(text: String) {
            column.addView(TextView(context).apply {
                this.text = text
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(context.themeColor(R.attr.bpTextDim))
                val d = resources.displayMetrics.density
                setPadding((4 * d).toInt(), (16 * d).toInt(), 0, (6 * d).toInt())
            })
        }

        fun row(label: String, icon: Int?, choice: Choice) {
            val item = ItemActionBinding.inflate(inflater, column, false)
            item.label.text = label
            item.icon.isVisible = icon != null
            icon?.let { item.icon.setImageResource(it) }
            item.root.setOnClickListener {
                dialog.dismiss()
                onPicked(choice)
            }
            column.addView(item.root)
        }

        Actions.Group.entries.filter { it in groups }.forEach { group ->
            val entries = Actions.all.filter { it.group == group }
            if (entries.isEmpty()) return@forEach
            header(context.getString(group.labelRes))
            entries.forEach { named ->
                row(Actions.label(context, named), named.iconRes, Choice(named.action, named))
            }
        }

        if (includeMacros) {
            val macros = MacroStore(context).all()
            if (macros.isNotEmpty()) {
                header(context.getString(R.string.group_macros))
                macros.forEach { macro ->
                    row(macro.name, R.drawable.ic_magic_wand, Choice(Action.RunMacro(macro.id), null, macro))
                }
            }
        }

        dialog.setContentView(ScrollView(context).apply {
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
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
}
