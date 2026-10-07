package com.devbangs.beampad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Macros as a page of their own, for places outside the Panels tab that
 * link to them. Draws the same list as the tab.
 */
class MacrosActivity : PageActivity() {

    override val wantsService = true

    private val host = object : Library.Host {
        override val hostActivity: Activity get() = this@MacrosActivity
        override fun refresh() = this@MacrosActivity.refresh()
        override fun openPanel(id: String) = Unit
        override fun runMacro(macro: Macro) = MacroRunSheet.run(this@MacrosActivity, service, macro)
    }

    override fun title(): CharSequence = getString(R.string.panels_tab_macros)
    override fun subtitle(): CharSequence = getString(R.string.macros_subtitle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.headerButton(page.header.root, getString(R.string.add_new), R.drawable.ic_plus) { Library.newMacro(host) }
    }

    override fun render() = Library.renderMacros(page.content, host)

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, MacrosActivity::class.java))
        }
    }
}
