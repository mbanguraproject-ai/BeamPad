package com.devbangs.beampad

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import com.devbangs.beampad.databinding.ActivityDocBinding

/** Displays a bundled plain-text document: privacy, terms or licences. */
class DocActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val ui = ActivityDocBinding.inflate(layoutInflater)
        setContentView(ui.root)

        WindowInsetsControllerCompat(window, ui.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val asset = intent.getStringExtra(EXTRA_ASSET) ?: "privacy.txt"
        val titleRes = intent.getIntExtra(EXTRA_TITLE, R.string.privacy_title)

        ui.bar.title.setText(titleRes)
        ui.bar.back.setOnClickListener { finish() }

        ui.body.text = runCatching {
            assets.open(asset).bufferedReader().use { it.readText() }
        }.getOrElse { "Could not load this document." }
    }

    companion object {
        const val EXTRA_ASSET = "asset"
        const val EXTRA_TITLE = "title"
    }
}
