package com.devbangs.beampad

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * Every popup in the app is one of these sheets: same handle, title,
 * spacing and footer. Pickers, confirmations, text prompts and editors all
 * build on [Sheet], so none of them can drift into a different look.
 */
class Sheet(val context: Context) {

    private val root: View = LayoutInflater.from(context).inflate(R.layout.ui_sheet, null)
    private val titleView: TextView = root.findViewById(R.id.title)
    private val subtitleView: TextView = root.findViewById(R.id.subtitle)
    private val footer: LinearLayout = root.findViewById(R.id.footer)
    private val primaryButton: MaterialButton = root.findViewById(R.id.primary)
    private val secondaryButton: MaterialButton = root.findViewById(R.id.secondary)
    private val footerGap: View = root.findViewById(R.id.footerGap)

    /** Where callers add their own views. */
    val content: LinearLayout = root.findViewById(R.id.content)

    val dialog = BottomSheetDialog(context).apply {
        setContentView(root)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
    }

    fun title(text: CharSequence): Sheet = apply { titleView.text = text }

    fun subtitle(text: CharSequence?): Sheet = apply {
        subtitleView.text = text
        subtitleView.isVisible = !text.isNullOrEmpty()
    }

    /** The main action. Return false from [onClick] to keep the sheet open (a validation failure). */
    fun primary(label: CharSequence, danger: Boolean = false, onClick: () -> Boolean): Sheet = apply {
        footer.isVisible = true
        primaryButton.isVisible = true
        primaryButton.text = label
        if (danger) {
            primaryButton.setBackgroundResource(R.drawable.bg_danger_button)
            primaryButton.setTextColor(context.themeColor(R.attr.bpDanger))
        }
        primaryButton.setOnClickListener { if (onClick()) dialog.dismiss() }
        footerGap.isVisible = secondaryButton.isVisible
    }

    fun secondary(label: CharSequence, onClick: () -> Unit = {}): Sheet = apply {
        footer.isVisible = true
        secondaryButton.isVisible = true
        secondaryButton.text = label
        secondaryButton.setOnClickListener {
            onClick()
            dialog.dismiss()
        }
        footerGap.isVisible = primaryButton.isVisible
    }

    /** Opens with the keyboard up, for sheets whose first job is typing. */
    fun withKeyboard(): Sheet = apply {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    fun onDismiss(block: () -> Unit): Sheet = apply { dialog.setOnDismissListener { block() } }

    fun show(): Sheet = apply { dialog.show() }

    fun dismiss() = dialog.dismiss()

    /** One selectable line in a picker. */
    fun option(
        title: CharSequence,
        detail: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        selected: Boolean = false,
        onClick: () -> Unit
    ): View {
        val view = LayoutInflater.from(context).inflate(R.layout.ui_option, content, false)
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.detail).apply {
            text = detail
            isVisible = !detail.isNullOrEmpty()
        }
        view.findViewById<ImageView>(R.id.icon).apply {
            isVisible = icon != null
            icon?.let { setImageResource(it) }
        }
        view.findViewById<View>(R.id.check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        view.setOnClickListener {
            Haptics.tick(it)
            dialog.dismiss()
            onClick()
        }
        content.addView(view)
        return view
    }
}

object Sheets {

    data class Choice<T>(val value: T, val title: CharSequence, val detail: CharSequence? = null, @DrawableRes val icon: Int? = null)

    /** Single choice: tap a line to pick it. The current value is ticked. */
    fun <T> choose(
        context: Context,
        title: CharSequence,
        choices: List<Choice<T>>,
        selected: T?,
        subtitle: CharSequence? = null,
        onPick: (T) -> Unit
    ): Sheet {
        val sheet = Sheet(context).title(title).subtitle(subtitle)
        choices.forEach { c ->
            sheet.option(c.title, c.detail, c.icon, c.value == selected) { onPick(c.value) }
        }
        return sheet.show()
    }

    fun confirm(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        confirmLabel: CharSequence,
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ): Sheet = Sheet(context)
        .title(title)
        .subtitle(message)
        .secondary(context.getString(R.string.cancel))
        .primary(confirmLabel, danger = destructive) {
            onConfirm()
            true
        }
        .show()

    /** One text field with Save. [validate] returns an error to show, or null to accept. */
    fun input(
        context: Context,
        title: CharSequence,
        label: CharSequence,
        initial: CharSequence? = null,
        hint: CharSequence? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        saveLabel: CharSequence = context.getString(R.string.save),
        validate: (String) -> String? = { if (it.isBlank()) context.getString(R.string.field_required) else null },
        onSave: (String) -> Unit
    ): Sheet {
        val sheet = Sheet(context).title(title).withKeyboard()
        val field = Ui.field(sheet.content, label, initial, hint, inputType)
        field.input.setSelection(field.input.text.length)
        sheet.secondary(context.getString(R.string.cancel))
        sheet.primary(saveLabel) {
            val value = field.text.trim()
            val error = validate(value)
            field.help(error)
            if (error == null) onSave(value)
            error == null
        }
        sheet.show()
        field.input.requestFocus()
        return sheet
    }
}
