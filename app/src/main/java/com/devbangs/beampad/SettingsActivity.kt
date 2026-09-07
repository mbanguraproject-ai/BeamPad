package com.devbangs.beampad

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import com.devbangs.beampad.databinding.ActivitySettingsBinding

class SettingsActivity : FragmentActivity() {

    private lateinit var ui: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("beampad", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ui = ActivitySettingsBinding.inflate(layoutInflater)
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

        ui.bar.title.setText(R.string.settings)
        ui.bar.back.setOnClickListener { finish() }

        updateLayoutRow()

        // Layout is read straight from prefs: this screen does not bind the
        // service, and the service reads the same key when it types.
        ui.rowLayout.setOnClickListener {
            val all = HidReports.Layout.entries
            val next = all[(all.indexOf(currentLayout()) + 1) % all.size]
            prefs.edit().putString("layout", next.name).apply()
            updateLayoutRow()
        }

        ui.rowProbe.setOnClickListener {
            Toast.makeText(this, R.string.probe_from_keyboard, Toast.LENGTH_LONG).show()
        }

        ui.rowPrivacy.setOnClickListener { openDoc("privacy.txt", R.string.privacy_title) }
        ui.rowTerms.setOnClickListener { openDoc("terms.txt", R.string.terms_title) }
        ui.rowLicences.setOnClickListener { openDoc("licences.txt", R.string.licences_title) }

        val pkg = packageManager.getPackageInfo(packageName, 0)
        ui.version.text = getString(R.string.version_format, pkg.versionName)
    }

    private fun currentLayout(): HidReports.Layout =
        runCatching {
            HidReports.Layout.valueOf(
                prefs.getString("layout", null) ?: HidReports.Layout.US.name
            )
        }.getOrDefault(HidReports.Layout.US)

    private fun updateLayoutRow() {
        ui.layoutValue.text = currentLayout().label
    }

    private fun openDoc(asset: String, titleRes: Int) {
        startActivity(
            Intent(this, DocActivity::class.java)
                .putExtra(DocActivity.EXTRA_ASSET, asset)
                .putExtra(DocActivity.EXTRA_TITLE, titleRes)
        )
    }
}
