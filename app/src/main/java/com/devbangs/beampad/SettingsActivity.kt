package com.devbangs.beampad

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import com.devbangs.beampad.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : FragmentActivity() {

    private lateinit var ui: ActivitySettingsBinding
    private val app get() = application as BeamPadApp
    private val prefs get() = app.prefs

    private val entitlementObserver: (Entitlements.Change) -> Unit = { render() }

    /**
     * Bound only if the service is already running (no auto-create), so
     * opening Settings never starts Bluetooth on its own. Used for the test
     * characters, which need a live connection.
     */
    private var service: HidService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as HidService.LocalBinder).service
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

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
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        ui.bar.title.setText(R.string.settings)
        ui.bar.back.setOnClickListener { finish() }

        ui.proCard.setOnClickListener { ProActivity.open(this, null) }

        // Connection
        bindSwitch(ui.rowReconnect, ui.switchReconnect, { prefs.autoReconnect }) {
            prefs.autoReconnect = it
        }
        bindSwitch(ui.rowScreenOn, ui.switchScreenOn, { prefs.keepScreenOn }) {
            prefs.keepScreenOn = it
        }
        ui.rowForget.setOnClickListener {
            prefs.lastHost = null
            render()
            Toast.makeText(this, R.string.settings_forgot, Toast.LENGTH_SHORT).show()
        }

        // Typing. Layout is read straight from prefs, and the service reads
        // the same key when it types.
        ui.rowLayout.setOnClickListener {
            val all = HidReports.Layout.entries
            prefs.layout = all[(all.indexOf(prefs.layout) + 1) % all.size]
            render()
        }

        ui.rowProbe.setOnClickListener { sendProbe() }

        ui.rowLive.setOnClickListener {
            if (!requirePro(Features.Pro.LIVE_TYPING)) return@setOnClickListener
            prefs.liveTyping = !prefs.liveTyping
            render()
        }
        ui.switchLive.setOnClickListener {
            if (!requirePro(Features.Pro.LIVE_TYPING)) {
                render()
                return@setOnClickListener
            }
            prefs.liveTyping = ui.switchLive.isChecked
        }

        bindSwitch(ui.rowHaptics, ui.switchHaptics, { prefs.haptics }) {
            prefs.haptics = it
        }

        // Trackpad (Pro)
        ui.sliderPointer.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.pointerSpeed = value
            ui.pointerValue.text = multiplier(value / Prefs.DEFAULT_POINTER_SPEED)
        }
        ui.sliderScroll.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.scrollSpeed = value
            ui.scrollValue.text = multiplier(value)
        }
        ui.rowReverse.setOnClickListener {
            if (!requirePro(Features.Pro.TRACKPAD)) return@setOnClickListener
            prefs.reverseScroll = !prefs.reverseScroll
            render()
        }
        ui.switchReverse.setOnClickListener {
            if (!requirePro(Features.Pro.TRACKPAD)) {
                render()
                return@setOnClickListener
            }
            prefs.reverseScroll = ui.switchReverse.isChecked
        }
        listOf(ui.rowPointer, ui.rowScroll).forEach { row ->
            row.setOnClickListener { requirePro(Features.Pro.TRACKPAD) }
        }

        // Purchases
        ui.rowRestore.setOnClickListener { restore() }

        // AdMob requires a way to revisit the consent choice, but only where
        // a consent form applies in the first place.
        if (Consent.privacyOptionsRequired(this)) {
            ui.rowPrivacyOptions.visibility = View.VISIBLE
            ui.rowPrivacyOptions.setOnClickListener { Consent.showPrivacyOptions(this) }
        }

        // About
        ui.rowRate.setOnClickListener { openStoreListing() }
        ui.rowPrivacy.setOnClickListener { openDoc("privacy.txt", R.string.privacy_title) }
        ui.rowTerms.setOnClickListener { openDoc("terms.txt", R.string.terms_title) }
        ui.rowLicences.setOnClickListener { openDoc("licences.txt", R.string.licences_title) }

        val pkg = packageManager.getPackageInfo(packageName, 0)
        ui.version.text = getString(R.string.version_format, pkg.versionName)

        app.observeEntitlement(entitlementObserver)

        bound = runCatching {
            bindService(Intent(this, HidService::class.java), connection, 0)
        }.getOrDefault(false)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.stopObservingEntitlement(entitlementObserver)
        if (bound) runCatching { unbindService(connection) }
    }

    private fun render() {
        val pro = Features.isPro(this)

        ui.proTitle.setText(if (pro) R.string.settings_pro_active else R.string.settings_pro_title)
        ui.proBody.setText(if (pro) R.string.settings_pro_active_body else R.string.settings_pro_body)

        ui.switchReconnect.isChecked = prefs.autoReconnect
        ui.switchScreenOn.isChecked = prefs.keepScreenOn
        ui.switchHaptics.isChecked = prefs.haptics

        val lastName = service?.lastHostName()
        ui.forgetValue.text = when {
            prefs.lastHost == null -> getString(R.string.settings_forget_none)
            lastName != null -> lastName
            else -> getString(R.string.settings_forget_hint)
        }
        ui.rowForget.isEnabled = prefs.lastHost != null
        ui.rowForget.alpha = if (prefs.lastHost != null) 1f else 0.5f

        ui.layoutValue.text = prefs.layout.label

        // Pro rows: the stored choice shows only when it is in effect.
        ui.lockLive.isVisible = !pro
        ui.switchLive.isChecked = Features.liveTyping(this)

        ui.lockTrackpad.isVisible = !pro
        val pointer = Features.pointerSpeed(this)
        val scroll = Features.scrollSpeed(this)
        ui.sliderPointer.value = snap(pointer, ui.sliderPointer.valueFrom, ui.sliderPointer.valueTo, ui.sliderPointer.stepSize)
        ui.sliderScroll.value = snap(scroll, ui.sliderScroll.valueFrom, ui.sliderScroll.valueTo, ui.sliderScroll.stepSize)
        ui.sliderPointer.isEnabled = pro
        ui.sliderScroll.isEnabled = pro
        ui.pointerValue.text = multiplier(pointer / Prefs.DEFAULT_POINTER_SPEED)
        ui.scrollValue.text = multiplier(scroll)
        ui.switchReverse.isChecked = Features.reverseScroll(this)
    }

    /**
     * Wires a whole row and its switch to one boolean. Tapping anywhere on
     * the row flips it, which is what people expect from a settings list.
     */
    private fun bindSwitch(
        row: View,
        switch: com.google.android.material.materialswitch.MaterialSwitch,
        read: () -> Boolean,
        write: (Boolean) -> Unit
    ) {
        row.setOnClickListener {
            write(!read())
            switch.isChecked = read()
        }
        switch.setOnClickListener { write(switch.isChecked) }
    }

    private fun requirePro(feature: Features.Pro): Boolean {
        if (Features.isPro(this)) return true
        ProActivity.open(this, feature)
        return false
    }

    private fun sendProbe() {
        val s = service
        if (s == null || !s.isReady()) {
            Toast.makeText(this, R.string.probe_needs_connection, Toast.LENGTH_LONG).show()
            return
        }
        s.typeText(HidReports.PROBE) { _, _ -> }
        Toast.makeText(this, getString(R.string.probe_sent, HidReports.PROBE), Toast.LENGTH_LONG).show()
    }

    private fun restore() {
        ui.rowRestore.isEnabled = false
        app.billing.restore { answered ->
            ui.rowRestore.isEnabled = true
            val message = when {
                app.entitlements.isPro -> R.string.restore_pro
                app.entitlements.adsRemoved -> R.string.restore_ads_only
                answered -> R.string.restore_none
                else -> R.string.restore_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            render()
        }
    }

    /** Links to the listing rather than asking for a review: never a policy problem. */
    private fun openStoreListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        )
        if (runCatching { startActivity(market) }.isFailure) {
            runCatching { startActivity(web) }
        }
    }

    /** Sliders throw if given a value off their step grid or outside the range. */
    private fun snap(value: Float, from: Float, to: Float, step: Float): Float {
        val clamped = value.coerceIn(from, to)
        val steps = Math.round((clamped - from) / step)
        return (from + steps * step).coerceIn(from, to)
    }

    private fun multiplier(value: Float): String =
        String.format(Locale.getDefault(), "%.1f×", value)

    private fun openDoc(asset: String, titleRes: Int) {
        startActivity(
            Intent(this, DocActivity::class.java)
                .putExtra(DocActivity.EXTRA_ASSET, asset)
                .putExtra(DocActivity.EXTRA_TITLE, titleRes)
        )
    }
}
