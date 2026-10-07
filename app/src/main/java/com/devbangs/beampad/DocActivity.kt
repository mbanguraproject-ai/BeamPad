package com.devbangs.beampad

import android.widget.TextView

/** Displays a bundled plain-text document: privacy, terms or licences. */
class DocActivity : PageActivity() {

    override fun title(): CharSequence = getString(intent.getIntExtra(EXTRA_TITLE, R.string.privacy_title))

    override fun render() {
        val asset = intent.getStringExtra(EXTRA_ASSET) ?: "privacy.txt"
        val text = runCatching {
            assets.open(asset).bufferedReader().use { it.readText() }
        }.getOrElse { getString(R.string.doc_unavailable) }
        val card = Ui.card(page.content)
        card.addView(TextView(this).apply {
            setTextAppearance(R.style.Text_Body_Dim)
            this.text = text
            setLineSpacing(Ui.dp(context, 4).toFloat(), 1f)
            setTextIsSelectable(true)
            val pad = Ui.dp(context, 20)
            setPadding(pad, pad, pad, pad)
        })
    }

    companion object {
        const val EXTRA_ASSET = "asset"
        const val EXTRA_TITLE = "title"
    }
}
