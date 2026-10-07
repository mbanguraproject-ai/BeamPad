package com.devbangs.beampad

import android.content.Context
import android.content.Intent

/**
 * What works with BeamPad, inside the app rather than buried in the store
 * listing. Grounded in what each platform's Bluetooth input support
 * actually accepts, not in marketing: where support varies by model, it
 * says so, and the quick test at the top settles it for any device.
 */
class CompatibilityActivity : PageActivity() {

    private enum class Level(val label: Int, val tone: Ui.Tone) {
        FULL(R.string.compat_full, Ui.Tone.LIVE),
        MOST(R.string.compat_most, Ui.Tone.ACCENT),
        KEYBOARD(R.string.compat_keyboard_only, Ui.Tone.NEUTRAL),
        VARIES(R.string.compat_varies, Ui.Tone.NEUTRAL),
        NONE(R.string.compat_none, Ui.Tone.DANGER)
    }

    private data class Entry(val name: Int, val detail: Int, val icon: Int, val level: Level)

    override fun title(): CharSequence = getString(R.string.compat_title)

    override fun render() {
        val content = page.content

        val intro = Ui.card(content)
        Ui.row(intro, getString(R.string.compat_test_title), getString(R.string.compat_test_body),
            R.drawable.ic_lightbulb, Ui.Tone.ACCENT).subtitle.maxLines = 6

        group(content, R.string.compat_group_tv, listOf(
            Entry(R.string.compat_googletv, R.string.compat_googletv_body, R.drawable.ic_television_simple, Level.FULL),
            Entry(R.string.compat_firetv, R.string.compat_firetv_body, R.drawable.ic_television_simple, Level.MOST),
            Entry(R.string.compat_samsung, R.string.compat_samsung_body, R.drawable.ic_television, Level.MOST),
            Entry(R.string.compat_lg, R.string.compat_lg_body, R.drawable.ic_television, Level.VARIES),
            Entry(R.string.compat_appletv, R.string.compat_appletv_body, R.drawable.ic_television_simple, Level.KEYBOARD),
            Entry(R.string.compat_roku, R.string.compat_roku_body, R.drawable.ic_television_simple, Level.NONE)
        ))
        group(content, R.string.compat_group_computers, listOf(
            Entry(R.string.compat_windows, R.string.compat_windows_body, R.drawable.ic_desktop, Level.FULL),
            Entry(R.string.compat_mac, R.string.compat_mac_body, R.drawable.ic_laptop, Level.FULL),
            Entry(R.string.compat_chromeos, R.string.compat_chromeos_body, R.drawable.ic_laptop, Level.FULL),
            Entry(R.string.compat_linux, R.string.compat_linux_body, R.drawable.ic_desktop, Level.FULL),
            Entry(R.string.compat_minipc, R.string.compat_minipc_body, R.drawable.ic_desktop, Level.FULL)
        ))
        group(content, R.string.compat_group_other, listOf(
            Entry(R.string.compat_projector, R.string.compat_projector_body, R.drawable.ic_projector_screen, Level.VARIES),
            Entry(R.string.compat_tablet, R.string.compat_tablet_body, R.drawable.ic_device_mobile, Level.FULL),
            Entry(R.string.compat_ipad, R.string.compat_ipad_body, R.drawable.ic_device_mobile, Level.MOST)
        ))

        Ui.section(content, getString(R.string.compat_group_tips))
        val tips = Ui.card(content)
        Ui.row(tips, getString(R.string.compat_tip_layout), getString(R.string.compat_tip_layout_body),
            R.drawable.ic_keyboard).subtitle.maxLines = 6
        Ui.divider(tips)
        Ui.row(tips, getString(R.string.compat_tip_phone), getString(R.string.compat_tip_phone_body),
            R.drawable.ic_device_mobile).subtitle.maxLines = 6
        Ui.divider(tips)
        Ui.linkRow(tips, getString(R.string.diag_title), getString(R.string.diag_row_body), R.drawable.ic_stethoscope) {
            DiagnosticsActivity.open(this)
        }
    }

    private fun group(parent: android.view.ViewGroup, title: Int, entries: List<Entry>) {
        Ui.section(parent, getString(title))
        val card = Ui.card(parent)
        entries.forEachIndexed { i, e ->
            if (i > 0) Ui.divider(card)
            val row = Ui.row(card, getString(e.name), getString(e.detail), e.icon)
            row.subtitle.maxLines = 4
            row.trailing.addView(Ui.tag(this, getString(e.level.label), e.level.tone))
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, CompatibilityActivity::class.java))
        }
    }
}
