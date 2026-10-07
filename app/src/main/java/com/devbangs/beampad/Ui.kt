package com.devbangs.beampad

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * The component library for screens assembled in code. Each function
 * inflates one of the ui_* templates, so a row built here is the same row
 * as one written in XML: same type, spacing, shape and press feedback.
 */
object Ui {

    fun dp(context: Context, value: Number): Int =
        (value.toFloat() * context.resources.displayMetrics.density).roundToInt()

    private fun inflate(parent: ViewGroup, layout: Int): View =
        LayoutInflater.from(parent.context).inflate(layout, parent, false)

    /** How a row's icon tile is tinted: neutral by default, live or accent to draw the eye. */
    enum class Tone(val tile: Int, val tint: Int) {
        NEUTRAL(R.drawable.bg_icon_tile, R.attr.bpText),
        ACCENT(R.drawable.bg_icon_tile_accent, R.attr.bpAccent2),
        LIVE(R.drawable.bg_icon_tile_live, R.attr.bpLive),
        DANGER(R.drawable.bg_icon_tile, R.attr.bpDanger)
    }

    // ---- Page structure -----------------------------------------------------

    /** Binds a ui_page_header already in a layout. */
    fun header(
        root: View,
        title: CharSequence,
        subtitle: CharSequence? = null,
        onBack: (() -> Unit)? = null
    ) {
        root.findViewById<TextView>(R.id.title).text = title
        root.findViewById<TextView>(R.id.subtitle).apply {
            text = subtitle
            isVisible = !subtitle.isNullOrEmpty()
        }
        root.findViewById<ImageButton>(R.id.back).apply {
            isVisible = onBack != null
            setOnClickListener { onBack?.invoke() }
        }
    }

    /** Adds a trailing action to a header: an icon button or a small pill. */
    fun headerIcon(header: View, @DrawableRes icon: Int, description: CharSequence, onClick: () -> Unit): ImageButton {
        val actions = header.findViewById<LinearLayout>(R.id.actions)
        val button = inflate(actions, R.layout.ui_icon_button) as ImageButton
        button.setImageResource(icon)
        button.contentDescription = description
        button.setOnClickListener { onClick() }
        if (actions.childCount > 0) {
            (button.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(header.context, 8)
        }
        actions.addView(button)
        return button
    }

    fun headerButton(header: View, label: CharSequence, @DrawableRes icon: Int?, primary: Boolean = false, onClick: () -> Unit): MaterialButton {
        val actions = header.findViewById<LinearLayout>(R.id.actions)
        val button = inflate(
            actions,
            if (primary) R.layout.ui_button_small_primary else R.layout.ui_button_small
        ) as MaterialButton
        button.text = label
        if (icon != null) button.setIconResource(icon)
        button.setOnClickListener { onClick() }
        if (actions.childCount > 0) {
            (button.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(header.context, 8)
        }
        actions.addView(button)
        return button
    }

    fun section(parent: ViewGroup, title: CharSequence): TextView =
        (inflate(parent, R.layout.ui_section_label) as TextView).also {
            it.text = title
            parent.addView(it)
        }

    fun card(parent: ViewGroup): LinearLayout =
        (inflate(parent, R.layout.ui_card) as LinearLayout).also { parent.addView(it) }

    fun divider(parent: ViewGroup): View = inflate(parent, R.layout.ui_divider).also { parent.addView(it) }

    fun space(parent: ViewGroup, heightDp: Int) {
        parent.addView(View(parent.context), ViewGroup.LayoutParams(1, dp(parent.context, heightDp)))
    }

    // ---- Rows ---------------------------------------------------------------

    class Row(val root: View) {
        val title: TextView = root.findViewById(R.id.title)
        val subtitle: TextView = root.findViewById(R.id.subtitle)
        val icon: ImageView = root.findViewById(R.id.icon)
        val iconTile: FrameLayout = root.findViewById(R.id.iconTile)
        val badge: TextView = root.findViewById(R.id.badge)
        val trailing: FrameLayout = root.findViewById(R.id.trailing)

        fun subtitle(text: CharSequence?) {
            subtitle.text = text
            subtitle.isVisible = !text.isNullOrEmpty()
        }

        fun icon(@DrawableRes res: Int?, tone: Tone = Tone.NEUTRAL) {
            iconTile.isVisible = res != null
            if (res == null) return
            icon.setImageResource(res)
            iconTile.setBackgroundResource(tone.tile)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(root.context.themeColor(tone.tint))
        }

        /** The Pro tag beside the title, for rows that open the paywall when locked. */
        fun pro(locked: Boolean) {
            badge.isVisible = locked
        }

        fun enabled(on: Boolean) {
            root.isEnabled = on
            root.alpha = if (on) 1f else 0.45f
        }
    }

    fun row(
        parent: ViewGroup,
        title: CharSequence,
        subtitle: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        tone: Tone = Tone.NEUTRAL,
        onClick: (() -> Unit)? = null
    ): Row {
        val view = inflate(parent, R.layout.ui_row)
        val row = Row(view)
        row.title.text = title
        row.subtitle(subtitle)
        row.icon(icon, tone)
        if (onClick != null) {
            view.setOnClickListener { onClick() }
        } else {
            view.background = null
            view.isClickable = false
        }
        parent.addView(view)
        return row
    }

    /** A row whose whole surface flips a switch. */
    fun switchRow(
        parent: ViewGroup,
        title: CharSequence,
        subtitle: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        checked: Boolean,
        onChange: (Boolean) -> Boolean
    ): Pair<Row, MaterialSwitch> {
        lateinit var switch: MaterialSwitch
        val row = row(parent, title, subtitle, icon) {
            // The callback may refuse (a Pro gate), so the switch shows
            // what was stored, not what was tapped.
            switch.isChecked = onChange(!switch.isChecked)
        }
        switch = inflate(row.trailing, R.layout.ui_switch) as MaterialSwitch
        switch.isChecked = checked
        row.trailing.addView(switch)
        return row to switch
    }

    /** A row showing its current value, opening a picker on tap. */
    fun valueRow(
        parent: ViewGroup,
        title: CharSequence,
        value: CharSequence,
        subtitle: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        onClick: () -> Unit
    ): Pair<Row, TextView> {
        val row = row(parent, title, subtitle, icon, onClick = onClick)
        val trailing = inflate(row.trailing, R.layout.ui_value)
        val valueView = trailing.findViewById<TextView>(R.id.value)
        valueView.text = value
        row.trailing.addView(trailing)
        return row to valueView
    }

    /** A row that navigates somewhere: chevron, no value. */
    fun linkRow(
        parent: ViewGroup,
        title: CharSequence,
        subtitle: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        tone: Tone = Tone.NEUTRAL,
        onClick: () -> Unit
    ): Row {
        val row = row(parent, title, subtitle, icon, tone, onClick)
        row.trailing.addView(ImageView(parent.context).apply {
            setImageResource(R.drawable.ic_caret_right)
            imageTintList = android.content.res.ColorStateList.valueOf(context.themeColor(R.attr.bpTextFaint))
            layoutParams = FrameLayout.LayoutParams(dp(context, 16), dp(context, 16))
        })
        return row
    }

    /** A titled row with a slider under it and the value on the right. */
    fun sliderRow(
        parent: ViewGroup,
        title: CharSequence,
        @DrawableRes icon: Int? = null,
        from: Float,
        to: Float,
        step: Float,
        value: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ): Pair<Row, Slider> {
        val row = row(parent, title, null, icon)
        val valueView = TextView(parent.context).apply {
            setTextAppearance(R.style.Text_Small)
            text = format(value)
        }
        row.trailing.addView(valueView)
        val slider = inflate(parent, R.layout.ui_slider) as Slider
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = step
        slider.value = snap(value, from, to, step)
        slider.addOnChangeListener { _, v, fromUser ->
            valueView.text = format(v)
            if (fromUser) onChange(v)
        }
        (slider.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
            if (icon != null) dp(parent.context, 52) else dp(parent.context, 12)
        parent.addView(slider)
        return row to slider
    }

    /** Sliders throw if given a value off their step grid or outside the range. */
    fun snap(value: Float, from: Float, to: Float, step: Float): Float {
        val clamped = value.coerceIn(from, to)
        val steps = ((clamped - from) / step).roundToInt()
        return (from + steps * step).coerceIn(from, to)
    }

    // ---- Controls -----------------------------------------------------------

    enum class ButtonKind(val layout: Int) {
        PRIMARY(R.layout.ui_button_primary),
        SECONDARY(R.layout.ui_button_secondary),
        DANGER(R.layout.ui_button_danger),
        SMALL(R.layout.ui_button_small),
        SMALL_PRIMARY(R.layout.ui_button_small_primary),
        TEXT(R.layout.ui_button_text)
    }

    fun button(parent: ViewGroup, kind: ButtonKind, label: CharSequence, @DrawableRes icon: Int? = null, onClick: () -> Unit): MaterialButton {
        val button = inflate(parent, kind.layout) as MaterialButton
        button.text = label
        if (icon != null) button.setIconResource(icon)
        button.setOnClickListener { onClick() }
        return button
    }

    fun chip(parent: ViewGroup, label: CharSequence, selected: Boolean, onClick: () -> Unit): TextView {
        val chip = inflate(parent, R.layout.ui_chip) as TextView
        chip.text = label
        chip.isSelected = selected
        chip.setOnClickListener {
            Haptics.tick(it)
            onClick()
        }
        parent.addView(chip)
        return chip
    }

    class Field(val root: View) {
        val label: TextView = root.findViewById(R.id.label)
        val input: EditText = root.findViewById(R.id.input)
        val help: TextView = root.findViewById(R.id.help)
        val text: String get() = input.text?.toString().orEmpty()

        fun help(text: CharSequence?) {
            help.text = text
            help.isVisible = !text.isNullOrEmpty()
        }
    }

    fun field(
        parent: ViewGroup,
        label: CharSequence,
        value: CharSequence? = null,
        hint: CharSequence? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        multiLine: Boolean = false
    ): Field {
        val field = Field(inflate(parent, R.layout.ui_field))
        field.label.text = label
        field.input.hint = hint
        field.input.inputType = if (multiLine) inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE else inputType
        if (multiLine) {
            field.input.minLines = 3
            field.input.maxLines = 6
            field.input.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        } else {
            field.input.maxLines = 1
            field.input.isSingleLine = true
        }
        field.input.setText(value)
        parent.addView(field.root)
        return field
    }

    /** A numbered instruction line. */
    fun step(parent: ViewGroup, number: Int, text: CharSequence): View {
        val view = inflate(parent, R.layout.ui_step)
        view.findViewById<TextView>(R.id.number).text = number.toString()
        view.findViewById<TextView>(R.id.text).text = text
        parent.addView(view)
        return view
    }

    /** A horizontal row of chips, one selected, for switching what a sheet shows. */
    fun <T> chipGroup(parent: ViewGroup, options: List<Pair<T, CharSequence>>, selected: T, onPick: (T) -> Unit): LinearLayout {
        val row = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL }
        fun render(current: T) {
            row.removeAllViews()
            options.forEach { (value, label) ->
                chip(row, label, value == current) {
                    render(value)
                    onPick(value)
                }
            }
        }
        render(selected)
        val scroll = android.widget.HorizontalScrollView(parent.context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
        parent.addView(scroll)
        return row
    }

    /** Fills an empty-state template and shows it. */
    fun empty(
        parent: ViewGroup,
        @DrawableRes icon: Int,
        title: CharSequence,
        body: CharSequence,
        action: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ): View {
        val view = inflate(parent, R.layout.ui_empty)
        view.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.body).text = body
        view.findViewById<MaterialButton>(R.id.action).apply {
            isVisible = action != null
            text = action
            setOnClickListener { onAction?.invoke() }
        }
        parent.addView(view)
        return view
    }

    /** Small rounded status tag: "Connected", "Pro", "3 steps". */
    fun tag(context: Context, text: CharSequence, tone: Tone = Tone.NEUTRAL): TextView =
        TextView(context).apply {
            this.text = text
            setTextAppearance(R.style.Text_Label_Small)
            setBackgroundResource(
                when (tone) {
                    Tone.LIVE -> R.drawable.bg_tag_mint
                    Tone.ACCENT -> R.drawable.bg_tag_accent
                    else -> R.drawable.bg_tag
                }
            )
            setTextColor(
                context.themeColor(
                    when (tone) {
                        Tone.LIVE -> R.attr.bpLive
                        Tone.ACCENT -> R.attr.bpAccent2
                        Tone.DANGER -> R.attr.bpDanger
                        Tone.NEUTRAL -> R.attr.bpTextDim
                    }
                )
            )
            val h = dp(context, 10)
            val v = dp(context, 3)
            setPadding(h, v, h, v)
        }
}
