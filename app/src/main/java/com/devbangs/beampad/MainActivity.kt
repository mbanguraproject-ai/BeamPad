package com.devbangs.beampad

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.devbangs.beampad.databinding.ActivityMainBinding
import com.devbangs.beampad.databinding.ItemDeviceBinding
import com.devbangs.beampad.databinding.SheetDevicesBinding
import com.devbangs.beampad.databinding.SheetPairBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : FragmentActivity() {

    private lateinit var ui: ActivityMainBinding

    var service: HidService? = null
        private set

    private val app get() = application as BeamPadApp
    private val prefs get() = app.prefs

    private var bound = false
    private var resumed = false
    private var sheet: BottomSheetDialog? = null

    /** Where the user is in getting connected. Rendered by [refreshStatus]. */
    private enum class Step {
        NEEDS_PERMISSION, NO_BLUETOOTH, BLUETOOTH_OFF, STARTING,
        UNSUPPORTED, READY, CONNECTING, CONNECTED
    }

    private val entitlementObserver: (Entitlements.Change) -> Unit = { change ->
        if (::ui.isInitialized) {
            // Same switch the remove-ads purchase always used. Pro reaches
            // it through Entitlements.adsRemoved, so the ad code is untouched.
            if (app.entitlements.adsRemoved) Ads.detach(ui.adSlot)
            renderPlan()
            val message = when (change) {
                Entitlements.Change.ADS_REMOVED -> R.string.ads_removed
                Entitlements.Change.PRO_STARTED -> R.string.pro_welcome
                Entitlements.Change.PRO_ENDED -> null
            }
            if (message != null && resumed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
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
            s.listener = { refreshStatus() }
            s.onNotice = { notice ->
                if (notice == HidService.Notice.CONNECT_FAILED) {
                    Toast.makeText(this@MainActivity, R.string.connect_failed, Toast.LENGTH_LONG).show()
                }
            }
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refreshStatus()
        }
    }

    /**
     * Android 12 introduced the Nearby devices permissions. Below that,
     * Bluetooth needs nothing at runtime, and asking for permissions the
     * platform does not know returns "denied", which used to leave older
     * phones unable to start at all.
     */
    private val bluetoothPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyArray()
        }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasBluetoothPermissions()) startAndBind()
        refreshStatus()
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshStatus() }

    private val discoverable = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // The result code is the visible duration when accepted.
        if (result.resultCode != Activity.RESULT_CANCELED) showPairSteps()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The app starts faster than the splash animation runs, so without a
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
        // stop its background short of the screen edge. Cutouts are included
        // for phones with a notch on the side in landscape.
        ViewCompat.setOnApplyWindowInsetsListener(ui.contentColumn) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
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

        ui.getPro.setOnClickListener { ProActivity.open(this, null) }

        ui.statusAction.setOnClickListener { onStatusAction() }

        ui.connectedRow.setOnClickListener { showDevicePicker() }

        ui.disconnect.setOnClickListener { disconnect() }

        if (savedInstanceState == null) {
            ui.bottomNav.selectedItemId = R.id.tab_keyboard
        }

        app.observeEntitlement(entitlementObserver)
        if (!app.entitlements.adsRemoved) {
            Ads.attach(this, ui.adSlot, app.entitlements)
        }
        renderPlan()

        if (hasBluetoothPermissions()) {
            startAndBind()
        } else if (!prefs.askedPermissions) {
            requestPermissions()
        }
        refreshStatus()

        if (savedInstanceState == null && !app.entitlements.isPro) {
            ui.getPro.postDelayed({ nudgeProButton() }, PRO_NUDGE_DELAY_MS)
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.tabContent, fragment)
            .commit()
    }

    private fun hasBluetoothPermissions(): Boolean =
        bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermissions() {
        // After two refusals Android stops showing the dialog and answers
        // "denied" at once, so a button that asks again would do nothing.
        // Settings is the only way left.
        val blocked = prefs.askedPermissions && bluetoothPermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(it)
        }
        if (blocked) {
            Toast.makeText(this, R.string.permission_in_settings, Toast.LENGTH_LONG).show()
            openAppSettings()
            return
        }

        prefs.askedPermissions = true
        val wanted = bluetoothPermissions.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isEmpty()) {
            startAndBind()
            return
        }
        permissionRequest.launch(wanted.toTypedArray())
    }

    private fun startAndBind() {
        if (bound) return
        val intent = Intent(this, HidService::class.java)
        // Can be refused when the app is not in the foreground; binding still
        // runs the service while the screen is open.
        runCatching { ContextCompat.startForegroundService(this, intent) }
        bound = runCatching {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    private fun adapter(): BluetoothAdapter? =
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    private fun currentStep(): Step {
        if (!hasBluetoothPermissions()) return Step.NEEDS_PERMISSION
        val s = service
        if (s == null) {
            val a = adapter() ?: return Step.NO_BLUETOOTH
            return if (runCatching { a.isEnabled }.getOrDefault(false)) Step.STARTING
            else Step.BLUETOOTH_OFF
        }
        return when (s.state) {
            HidService.State.NO_BLUETOOTH -> Step.NO_BLUETOOTH
            HidService.State.BLUETOOTH_OFF -> Step.BLUETOOTH_OFF
            HidService.State.STARTING -> Step.STARTING
            HidService.State.UNSUPPORTED -> Step.UNSUPPORTED
            HidService.State.READY -> Step.READY
            HidService.State.CONNECTING -> Step.CONNECTING
            HidService.State.CONNECTED ->
                if (s.isReady()) Step.CONNECTED else Step.READY
        }
    }

    private fun onStatusAction() {
        when (currentStep()) {
            Step.NEEDS_PERMISSION -> requestPermissions()
            Step.BLUETOOTH_OFF -> turnOnBluetooth()
            Step.UNSUPPORTED -> service?.restart()
            Step.READY -> showDevicePicker()
            else -> Unit
        }
    }

    private fun refreshStatus() {
        if (!::ui.isInitialized) return
        val step = currentStep()
        val s = service
        val connected = step == Step.CONNECTED

        TransitionManager.beginDelayedTransition(ui.contentColumn, AutoTransition().apply {
            duration = 200
        })

        ui.connectedRow.isVisible = connected
        ui.setupRow.isVisible = !connected

        if (connected) {
            ui.connectedName.text = s?.connectedDevice?.let { s.deviceLabel(it) }
            // A sheet about finding the TV has nothing left to say.
            sheet?.dismiss()
        } else {
            renderSetup(step, s)
        }

        if (connected && prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        connectionObservers.forEach { it(connected) }
    }

    private fun renderSetup(step: Step, s: HidService?) {
        val icon: Int
        val title: Int
        val detail: String
        val action: Int?

        when (step) {
            Step.NEEDS_PERMISSION -> {
                icon = R.drawable.ic_bluetooth
                title = R.string.status_permission
                detail = getString(R.string.status_permission_detail)
                action = R.string.action_allow
            }
            Step.NO_BLUETOOTH -> {
                icon = R.drawable.ic_bluetooth_slash
                title = R.string.status_no_bluetooth
                detail = getString(R.string.status_no_bluetooth_detail)
                action = null
            }
            Step.BLUETOOTH_OFF -> {
                icon = R.drawable.ic_bluetooth_slash
                title = R.string.status_bt_off
                detail = getString(R.string.status_bt_off_detail)
                action = R.string.action_turn_on
            }
            Step.STARTING -> {
                icon = R.drawable.ic_bluetooth
                title = R.string.status_starting
                detail = getString(R.string.status_starting_detail)
                action = null
            }
            Step.UNSUPPORTED -> {
                icon = R.drawable.ic_bluetooth_slash
                title = R.string.status_unsupported
                detail = getString(R.string.status_unsupported_detail)
                action = R.string.action_try_again
            }
            Step.READY -> {
                icon = R.drawable.ic_television_simple
                title = R.string.status_ready
                val last = s?.lastHostName()
                detail = if (last != null) getString(R.string.status_ready_last, last)
                else getString(R.string.status_ready_first)
                action = R.string.action_connect
            }
            Step.CONNECTING, Step.CONNECTED -> {
                icon = R.drawable.ic_television_simple
                title = R.string.status_connecting_short
                val target = s?.connectingDevice?.let { s.deviceLabel(it) }
                detail = if (target != null) getString(R.string.status_connecting_to, target)
                else getString(R.string.status_connecting_detail)
                action = null
            }
        }

        ui.statusIcon.setImageResource(icon)
        ui.statusTitle.setText(title)
        ui.statusDetail.text = detail
        ui.statusAction.isVisible = action != null
        action?.let { ui.statusAction.setText(it) }
        ui.statusProgress.isVisible = step == Step.STARTING || step == Step.CONNECTING
    }

    private fun renderPlan() {
        val pro = app.entitlements.isPro
        ui.proBadge.isVisible = pro
        ui.getPro.isVisible = !pro
        ui.planLabel.setText(if (pro) R.string.plan_pro_label else R.string.plan_free_label)
    }

    /** One gentle bounce per launch, so the button is noticed without nagging. */
    private fun nudgeProButton() {
        if (!::ui.isInitialized || !ui.getPro.isVisible) return
        ui.getPro.animate().scaleX(1.08f).scaleY(1.08f).setDuration(180).withEndAction {
            ui.getPro.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
        }.start()
    }

    private fun turnOnBluetooth() {
        val launched = runCatching {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.isSuccess
        if (!launched) openBluetoothSettings()
    }

    private fun showDevicePicker() {
        val s = service ?: return
        val hosts = s.pairedHosts()
        if (hosts.isEmpty()) {
            startPairing()
            return
        }

        val view = SheetDevicesBinding.inflate(layoutInflater)
        val dialog = newSheet()
        val current = s.connectedDevice

        hosts.forEach { device ->
            val row = ItemDeviceBinding.inflate(layoutInflater, view.deviceList, false)
            row.name.text = s.deviceLabel(device)
            row.icon.setImageResource(
                when (s.hostKind(device)) {
                    HidService.HostKind.TV -> R.drawable.ic_television_simple
                    HidService.HostKind.COMPUTER -> R.drawable.ic_desktop
                    HidService.HostKind.PHONE -> R.drawable.ic_device_mobile
                }
            )
            val isCurrent = device == current
            row.lastUsed.isVisible = isCurrent || (current == null && device.address == prefs.lastHost)
            row.lastUsed.setText(if (isCurrent) R.string.status_connected else R.string.last_used)
            row.root.setOnClickListener {
                dialog.dismiss()
                if (!isCurrent) {
                    s.connect(device)
                    refreshStatus()
                }
            }
            view.deviceList.addView(row.root)
        }

        view.pairNew.setOnClickListener {
            dialog.dismiss()
            startPairing()
        }

        dialog.setContentView(view.root)
        dialog.show()
    }

    /** Makes the phone visible, then shows where to look on the TV. */
    private fun startPairing() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
        // Launching this without the advertise permission is what used to
        // crash Pair on Android 12+. The step machine only offers pairing
        // once permissions are granted, and some ROMs have no such screen at
        // all, so failure falls back to the steps alone: many TVs still find
        // the phone while its Bluetooth settings are open.
        val launched = hasBluetoothPermissions() && runCatching {
            discoverable.launch(intent)
        }.isSuccess
        if (!launched) showPairSteps()
    }

    private fun showPairSteps() {
        val view = SheetPairBinding.inflate(layoutInflater)
        val dialog = newSheet()
        val name = service?.localBluetoothName() ?: getString(R.string.this_phone)
        view.step3.text = getString(R.string.pair_step_3, name)
        view.done.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view.root)
        dialog.show()
    }

    private fun newSheet(): BottomSheetDialog {
        sheet?.dismiss()
        return BottomSheetDialog(this).also { dialog ->
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.setOnDismissListener { if (sheet === dialog) sheet = null }
            sheet = dialog
        }
    }

    private fun disconnect() {
        val s = service ?: return
        val worked = s.sentThisSession
        if (!s.disconnect()) {
            Toast.makeText(this, R.string.disconnect_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // Only after a session that actually did something. Asking after
        // a failed pairing is exactly what Play penalises.
        if (worked) {
            Reviews.recordGoodSession(this)
            ui.disconnect.postDelayed({ Reviews.ask(this) }, 600)
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        Ads.pause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Ads.resume()
        if (!::ui.isInitialized) return
        // Permission may have been granted in system settings meanwhile.
        if (hasBluetoothPermissions()) startAndBind()
        renderPlan()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        sheet?.dismiss()
        app.stopObservingEntitlement(entitlementObserver)
        service?.listener = null
        service?.onNotice = null
        if (bound) runCatching { unbindService(connection) }
    }

    private companion object {
        const val SPLASH_HOLD_MS = 1300L
        const val PRO_NUDGE_DELAY_MS = 1600L
        const val DISCOVERABLE_SECONDS = 180
    }
}
