package com.devbangs.beampad

import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.widget.Toast
import android.os.IBinder
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.devbangs.beampad.databinding.ActivityMainBinding

class MainActivity : FragmentActivity() {

    private lateinit var ui: ActivityMainBinding

    var service: HidService? = null
        private set

    private val app get() = application as BeamPadApp

    private val entitlementObserver: () -> Unit = {
        runOnUiThread {
            Ads.detach(ui.adSlot)
            Toast.makeText(this, R.string.ads_removed, Toast.LENGTH_LONG).show()
        }
    }

    /** Fragments observe this to enable or disable their controls. */
    private val connectionObservers = mutableSetOf<(Boolean) -> Unit>()

    fun observeConnection(observer: (Boolean) -> Unit) {
        connectionObservers += observer
        observer(service?.isReady() == true)
    }

    fun stopObserving(observer: (Boolean) -> Unit) {
        connectionObservers -= observer
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as HidService.LocalBinder).service
            service = s
            s.listener = { runOnUiThread { refreshStatus() } }
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refreshStatus()
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.filterKeys { it != POST_NOTIF }.values.all { it }) startAndBind()
        else refreshStatus()
    }

    private val discoverable = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Must run before super.onCreate: it swaps the splash theme out for
        // the real one and hands the window over.
        //
        // The app starts faster than the ring animation runs, so without a
        // hold the splash flashes past and reads as a glitch. Held just long
        // enough to register as deliberate, not long enough to feel like a
        // stall.
        val splashShownAt = SystemClock.uptimeMillis()
        installSplashScreen().setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - splashShownAt < SPLASH_HOLD_MS
        }

        if (OnboardingActivity.shouldShow(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        // Some OEM skins ignore the theme flag, so set bar icon appearance
        // directly: dark background needs light icons.
        WindowInsetsControllerCompat(window, ui.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Top inset on the content column, bottom inset on the nav itself:
        // padding the whole column would leave dead space under the nav and
        // stop its background short of the screen edge.
        ViewCompat.setOnApplyWindowInsetsListener(ui.contentColumn) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(ui.bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        ui.bottomNav.setOnItemSelectedListener { item ->
            show(
                when (item.itemId) {
                    R.id.tab_keyboard -> KeyboardFragment()
                    R.id.tab_trackpad -> TrackpadFragment()
                    else -> SnippetsFragment()
                }
            )
            true
        }

        ui.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ui.connectedChip.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (!s.disconnect()) {
                Toast.makeText(this, R.string.disconnect_failed, Toast.LENGTH_SHORT).show()
            }
        }

        ui.statusAction.setOnClickListener {
            val s = service
            if (s?.isReady() == true) {
                if (!s.disconnect()) {
                    Toast.makeText(this, R.string.disconnect_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE))
            }
        }

        if (savedInstanceState == null) {
            ui.bottomNav.selectedItemId = R.id.tab_snippets
        }

        app.observeEntitlement(entitlementObserver)
        if (!app.entitlements.adsRemoved) {
            Ads.attach(this, ui.adSlot, app.entitlements)
        }

        permissions.launch(
            buildList {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
                add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                if (Build.VERSION.SDK_INT >= 33) add(POST_NOTIF)
            }.toTypedArray()
        )
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.tabContent, fragment)
            .commit()
    }

    private fun startAndBind() {
        val intent = Intent(this, HidService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun refreshStatus() {
        val s = service
        val connected = s?.isReady() == true
        val device = s?.connectedDevice

        // Connected: the pairing card has nothing left to say, so it goes and
        // its row is returned to the content. A chip in the header carries
        // the peer name and the disconnect action.
        TransitionManager.beginDelayedTransition(ui.contentColumn, AutoTransition().apply {
            duration = 220
        })

        if (connected && device != null) {
            ui.statusCard.visibility = View.GONE
            ui.connectedChip.visibility = View.VISIBLE
            ui.chipLabel.text = s.deviceLabel(device)
        } else {
            ui.statusCard.visibility = View.VISIBLE
            ui.connectedChip.visibility = View.GONE
            ui.statusIcon.setImageResource(R.drawable.ic_bluetooth)
            ui.statusTitle.setText(R.string.status_ready)
            ui.statusDot.visibility = View.GONE
            ui.statusDetail.text =
                getString(R.string.pair_instructions, s?.localBluetoothName() ?: "this phone")
            ui.statusDetail.visibility = View.VISIBLE
            ui.statusAction.setText(R.string.action_pair)
        }

        connectionObservers.forEach { it(connected) }
    }

    override fun onPause() {
        super.onPause()
        Ads.pause()
    }

    override fun onResume() {
        super.onResume()
        Ads.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.stopObservingEntitlement(entitlementObserver)
        service?.listener = null
        runCatching { unbindService(connection) }
    }

    private companion object {
        const val SPLASH_HOLD_MS = 1300L
        const val POST_NOTIF = "android.permission.POST_NOTIFICATIONS"
    }
}
