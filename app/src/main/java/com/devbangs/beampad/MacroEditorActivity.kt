package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import androidx.core.view.isVisible

/**
 * Builds a macro: named steps in order, with waits where the device needs
 * time and typed text where a search box needs it. Test runs it with the
 * same progress and Stop control as everywhere else.
 */
class MacroEditorActivity : PageActivity() {

    override val wantsService = true

    private val macroId: String get() = intent.getStringExtra(EXTRA_MACRO).orEmpty()
    private val store by lazy { MacroStore(this) }

    private fun macro(): Macro? = store.get(macroId)

    override fun title(): CharSequence = macro()?.name ?: getString(R.string.panels_tab_macros)

    override fun subtitle(): CharSequence {
        val m = macro() ?: return ""
        val total = m.steps.filterIsInstance<Action.Delay>().sumOf { it.millis }
        val steps = resources.getQuantityString(R.plurals.macro_steps, m.steps.size, m.steps.size)
        return if (total > 0) getString(R.string.macro_subtitle_wait, steps, total / 1000f) else steps
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (macro() == null) {
            finish()
            return
        }
        Ui.headerIcon(page.header.root, R.drawable.ic_pencil_simple, getString(R.string.rename)) { rename() }
        Ui.headerButton(page.header.root, getString(R.string.macro_test), R.drawable.ic_play, primary = true) {
            macro()?.let { MacroRunSheet.run(this, service, it) }
        }

        page.footer.isVisible = true
        listOf(
            Triple(R.string.macro_add_action, R.drawable.ic_plus) { addAction() },
            Triple(R.string.macro_add_wait, R.drawable.ic_timer) { addWait() },
            Triple(R.string.macro_add_text, R.drawable.ic_text_t) { addText() }
        ).forEachIndexed { i, (label, icon, onClick) ->
            val b = Ui.button(page.footer, Ui.ButtonKind.SMALL, getString(label), icon) { onClick() }
            page.footer.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = Ui.dp(this@MacroEditorActivity, 8)
            })
        }
    }

    override fun render() {
        val m = macro() ?: return
        val content = page.content

        if (m.steps.isEmpty()) {
            Ui.empty(content, R.drawable.ic_magic_wand, getString(R.string.macro_empty_title), getString(R.string.macro_empty_body))
        } else {
            Ui.section(content, getString(R.string.macro_steps_title))
            val card = Ui.card(content)
            m.steps.forEachIndexed { i, step ->
                if (i > 0) Ui.divider(card)
                val row = Ui.row(card, ActionPicker.describe(this, step), getString(R.string.macro_step_number, i + 1),
                    ActionPicker.icon(step), if (step is Action.Delay) Ui.Tone.NEUTRAL else Ui.Tone.ACCENT) {
                    stepActions(i, step)
                }
                row.trailing.addView(android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_dots_three)
                    imageTintList = android.content.res.ColorStateList.valueOf(themeColor(R.attr.bpTextDim))
                    layoutParams = android.widget.FrameLayout.LayoutParams(Ui.dp(context, 20), Ui.dp(context, 20))
                })
            }
        }

        Ui.section(content, getString(R.string.device_manage))
        val manage = Ui.card(content)
        Ui.row(manage, getString(R.string.macro_delete), null, R.drawable.ic_trash, Ui.Tone.DANGER) {
            Sheets.confirm(this, getString(R.string.macro_delete_confirm, m.name), getString(R.string.macro_delete_body),
                getString(R.string.delete), destructive = true) {
                store.delete(m.id)
                finish()
            }
        }.title.setTextColor(themeColor(R.attr.bpDanger))
    }

    private fun save(update: (Macro) -> Macro) {
        val current = macro() ?: return
        store.save(update(current))
        refresh()
    }

    private fun rename() {
        val m = macro() ?: return
        Sheets.input(this, getString(R.string.rename), getString(R.string.macro_name), m.name) { name ->
            save { it.copy(name = name) }
        }
    }

    private fun addAction(replace: Int? = null) {
        ActionPicker.show(this, getString(R.string.pick_action), includeMacros = true, excludeMacro = macroId) { choice ->
            save { m ->
                val steps = m.steps.toMutableList()
                if (replace != null) steps[replace] = choice.action else steps += choice.action
                m.copy(steps = steps)
            }
        }
    }

    private fun addWait(replace: Int? = null) {
        val current = (replace?.let { macro()?.steps?.getOrNull(it) } as? Action.Delay)?.millis
        Sheets.choose(this, getString(R.string.macro_add_wait), WAITS.map {
            Sheets.Choice(it, getString(R.string.macro_wait_seconds, it / 1000f))
        }, current, subtitle = getString(R.string.macro_wait_body)) { ms ->
            save { m ->
                val steps = m.steps.toMutableList()
                if (replace != null) steps[replace] = Action.Delay(ms) else steps += Action.Delay(ms)
                m.copy(steps = steps)
            }
        }
    }

    private fun addText(replace: Int? = null) {
        val current = (replace?.let { macro()?.steps?.getOrNull(it) } as? Action.Text)?.text
        Sheets.input(this, getString(R.string.macro_add_text), getString(R.string.macro_text_label), current,
            hint = getString(R.string.macro_text_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) { text ->
            save { m ->
                val steps = m.steps.toMutableList()
                if (replace != null) steps[replace] = Action.Text(text) else steps += Action.Text(text)
                m.copy(steps = steps)
            }
        }
    }

    private fun stepActions(index: Int, step: Action) {
        val m = macro() ?: return
        val sheet = Sheet(this).title(ActionPicker.describe(this, step))
            .subtitle(getString(R.string.macro_step_number, index + 1))
        sheet.option(getString(R.string.macro_change_step), icon = R.drawable.ic_pencil_simple) {
            when (step) {
                is Action.Delay -> addWait(replace = index)
                is Action.Text -> addText(replace = index)
                else -> addAction(replace = index)
            }
        }
        if (index > 0) sheet.option(getString(R.string.move_up), icon = R.drawable.ic_caret_up) {
            save { it.copy(steps = it.steps.toMutableList().apply { add(index - 1, removeAt(index)) }) }
        }
        if (index < m.steps.size - 1) sheet.option(getString(R.string.move_down), icon = R.drawable.ic_caret_down) {
            save { it.copy(steps = it.steps.toMutableList().apply { add(index + 1, removeAt(index)) }) }
        }
        sheet.option(getString(R.string.duplicate), icon = R.drawable.ic_copy_simple) {
            save { it.copy(steps = it.steps.toMutableList().apply { add(index + 1, step) }) }
        }
        sheet.option(getString(R.string.delete), icon = R.drawable.ic_trash) {
            save { it.copy(steps = it.steps.toMutableList().apply { removeAt(index) }) }
        }
        sheet.show()
    }

    companion object {
        const val EXTRA_MACRO = "macro"
        private val WAITS = listOf(250L, 500L, 1000L, 1500L, 2000L, 3000L, 5000L)

        /** Edits [macroId]. New macros are created by the caller first. */
        fun open(context: Context, macroId: String?) {
            context.startActivity(Intent(context, MacroEditorActivity::class.java).putExtra(EXTRA_MACRO, macroId))
        }
    }
}
