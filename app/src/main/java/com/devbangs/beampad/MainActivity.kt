package com.devbangs.beampad

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.devbangs.beampad.databinding.ActivityMainBinding
import com.devbangs.beampad.databinding.ItemDeviceBinding
import com.devbangs.beampad.databinding.RailHeaderBinding
import com.devbangs.beampad.databinding.SheetDevicesBinding
import com.devbangs.beampad.databinding.SheetPairBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.navigation.NavigationBarView

class MainActivity : BeamActivity() {

    private lateinit var ui: ActivityMainBinding

    /** The bottom bar on narrow windows, the side rail on wide ones. */
    private lateinit var nav: NavigationBarView
    private var railHeader: RailHeaderBinding? = null
    private var currentTab = R.id.tab_control

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
        UNSUPPORTED, READY, CONNECTING, CONNECTED, LOST
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

    private val stateListener: () -> Unit = { refreshStatus() }

    private val noticeListener: (HidService.Notice) -> Unit = { notice ->
        when (notice) {
            HidService.Notice.CONNECTED -> onConnected()
            HidService.Notice.CONNECT_FAILED ->
                Toast.makeText(this, R.string.connect_failed, Toast.LENGTH_LONG).show()
            HidService.Notice.GAVE_UP_RETRYING ->
                Toast.makeText(this, R.string.retry_gave_up, Toast.LENGTH_LONG).show()
            HidService.Notice.LOST -> Unit
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as HidService.LocalBinder).service
            service = s
            s.addListener(stateListener)
            s.addNoticeListener(noticeListener)
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
        // Before super.onCreate: it swaps the splash theme for the app theme.
        // No artificial hold: the app is usable as soon as it draws.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (OnboardingActivity.shouldShow(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        setUpNavigation()

        // Top inset on the content column, bottom inset on the bottom bar
        // itself: padding the whole column would leave dead space under the
        // bar and stop its background short of the screen edge. With the rail
        // there is no bottom bar, so the column takes the bottom inset too.
        // Cutouts are included for phones with a notch on the side in landscape.
        val railShown = nav === ui.navRail
        ViewCompat.setOnApplyWindowInsetsListener(ui.contentColumn) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, if (railShown) bars.bottom else 0)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(ui.bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        // The column already keeps the rail clear of the bars; without this
        // the rail would pad itself a second time.
        ViewCompat.setOnApplyWindowInsetsListener(ui.navRail) { _, insets -> insets }

        ui.settings.setOnClickListener { openSettings() }
        ui.getPro.setOnClickListener { ProActivity.open(this, null) }
        ui.statusAction.setOnClickListener { onStatusAction() }
        ui.connectedRow.setOnClickListener { showDevicePicker() }
        ui.disconnect.setOnClickListener { disconnect() }

        // Select before listening when restoring: the restored tab fragment
        // is already in place and must not be replaced by a fresh one.
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.tab_control)
            nav.selectedItemId = currentTab
        }
        nav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        if (savedInstanceState == null) nav.selectedItemId = R.id.tab_control

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

    /**
     * Wide windows (tablets, unfolded foldables, phones in landscape) get a
     * side rail instead of the bottom bar. Short ones (phones in landscape)
     * also drop the header row and move Get Pro and Settings into the rail,
     * so the control surface keeps enough height. Decided per configuration:
     * folding, unfolding and rotating recreate the activity.
     */
    private fun setUpNavigation() {
        val config = resources.configuration
        val wide = config.screenWidthDp >= RAIL_MIN_WIDTH_DP
        nav = if (wide) ui.navRail else ui.bottomNav
        ui.navRail.isVisible = wide
        ui.bottomNav.isVisible = !wide
        if (wide && config.screenHeightDp < COMPACT_HEIGHT_DP) {
            ui.headerRow.isVisible = false
            val header = RailHeaderBinding.inflate(layoutInflater, ui.navRail, false)
            header.railPro.setOnClickListener { ProActivity.open(this, null) }
            header.railSettings.setOnClickListener { openSettings() }
            ui.navRail.addHeaderView(header.root)
            railHeader = header
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Arriving from the notification while already open: just show Control.
        if (::ui.isInitialized) nav.selectedItemId = R.id.tab_control
    }

    private fun showTab(itemId: Int) {
        currentTab = itemId
        val tag = "tab_$itemId"
        if (supportFragmentManager.findFragmentByTag(tag)?.isVisible == true) return
        val fragment: Fragment = when (itemId) {
            R.id.tab_devices -> DevicesFragment()
            R.id.tab_panels -> PanelsFragment()
            R.id.tab_snippets -> SnippetsFragment()
            else -> ControlFragment()
        }
        val tx = supportFragmentManager.beginTransaction()
        if (!Motion.reduced(this)) tx.setCustomAnimations(R.animator.mode_in, R.animator.mode_out)
        tx.replace(R.id.tabContent, fragment, tag).commit()
    }

    /** The Control tab's fragment, when it is the one showing. */
    fun controlFragment(): ControlFragment? =
        supportFragmentManager.findFragmentById(R.id.tabContent) as? ControlFragment

    /** Switches to the Control tab and the given surface. Used by device profiles and Devices. */
    fun openControl(mode: ControlMode? = null, panelId: String? = null) {
        if (mode != null) prefs.lastMode = mode
        if (panelId != null) prefs.lastPanelId = panelId else if (mode != null) prefs.lastPanelId = null
        val current = controlFragment()
        if (current != null) {
            panelId?.let { current.showPanel(it) } ?: mode?.let { current.showMode(it) }
        } else {
            nav.selectedItemId = R.id.tab_control
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    fun requestPermissions() {
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
        AppOpenAds.skipNextReturn()
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
            HidService.State.DISCONNECTED -> Step.LOST
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
            Step.LOST -> service?.reconnect()
            else -> Unit
        }
    }

    private fun refreshStatus() {
        if (!::ui.isInitialized) return
        val step = currentStep()
        val s = service
        val connected = step == Step.CONNECTED

        if (!Motion.reduced(this)) {
            TransitionManager.beginDelayedTransition(ui.contentColumn, AutoTransition().apply {
                duration = 180
            })
        }

        ui.connectedRow.isVisible = connected
        ui.setupRow.isVisible = !connected

        if (connected) {
            val device = s?.connectedDevice
            ui.connectedName.text = device?.let { s.deviceLabel(it) }
            ui.connectedIcon.setImageResource(
                device?.let { s.typeOf(it).iconRes } ?: R.drawable.ic_television_simple
            )
            // A sheet about finding the device has nothing left to say.
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
                icon = R.drawable.ic_warning_circle
                title = R.string.status_unsupported
                detail = getString(
                    when (s?.unsupportedReason) {
                        HidService.UnsupportedReason.NO_PROFILE -> R.string.unsupported_no_profile
                        HidService.UnsupportedReason.REFUSED -> R.string.unsupported_refused
                        else -> R.string.unsupported_no_response
                    }
                )
                action = if (s?.unsupportedReason == HidService.UnsupportedReason.NO_PROFILE) null
                else R.string.action_try_again
            }
            Step.READY -> {
                icon = R.drawable.ic_devices
                title = R.string.status_ready
                val last = s?.lastHostName()
                detail = if (last != null) getString(R.string.status_ready_last, last)
                else getString(R.string.status_ready_first)
                action = R.string.action_connect
            }
            Step.LOST -> {
                val device = s?.lostDevice
                icon = device?.let { s.typeOf(it).iconRes } ?: R.drawable.ic_devices
                title = R.string.status_lost
                val name = device?.let { s.deviceLabel(it) } ?: getString(R.string.device_fallback)
                detail = if (s?.isRetrying == true) {
                    getString(R.string.status_lost_retrying, name)
                } else {
                    getString(R.string.status_lost_detail, name)
                }
                action = R.string.action_reconnect
            }
            Step.CONNECTING, Step.CONNECTED -> {
                val target = s?.connectingDevice
                icon = target?.let { s.typeOf(it).iconRes } ?: R.drawable.ic_devices
                title = if ((s?.retryAttempt ?: 0) > 0) R.string.status_reconnecting
                else R.string.status_connecting_short
                detail = if (target != null) getString(R.string.status_connecting_to, s.deviceLabel(target))
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

    /**
     * Medium haptic and optional tone to confirm the link, then the device
     * profile's preferred surface (Pro).
     */
    private fun onConnected() {
        Haptics.confirm(this)
        if (prefs.connectionSound) {
            runCatching {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).apply {
                    startTone(ToneGenerator.TONE_PROP_ACK, 150)
                    ui.root.postDelayed({ release() }, 400)
                }
            }
        }
        val s = service ?: return
        val device = s.connectedDevice ?: return
        if (!Features.profiles(this)) return
        val saved = DeviceStore(this).get(device.address) ?: return
        when {
            saved.defaultPanelId != null && PanelStore(this).get(saved.defaultPanelId) != null ->
                openControl(panelId = saved.defaultPanelId)
            saved.preferredMode != null -> openControl(mode = saved.preferredMode)
        }
    }

    private fun renderPlan() {
        val pro = app.entitlements.isPro
        ui.proBadge.isVisible = pro
        ui.getPro.isVisible = !pro
        railHeader?.railPro?.isVisible = !pro
        ui.planLabel.setText(if (pro) R.string.plan_pro_label else R.string.plan_free_label)
    }

    /** One gentle bounce, at most once a day, so the button is noticed without nagging. */
    private fun nudgeProButton() {
        if (!::ui.isInitialized || !ui.getPro.isVisible || Motion.reduced(this)) return
        val nudges = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val today = System.currentTimeMillis() / DAY_MS
        if (nudges.getLong(KEY_LAST_NUDGE, -1) == today) return
        nudges.edit().putLong(KEY_LAST_NUDGE, today).apply()
        ui.getPro.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160).withEndAction {
            ui.getPro.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
    }

    private fun turnOnBluetooth() {
        AppOpenAds.skipNextReturn()
        val launched = runCatching {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.isSuccess
        if (!launched) openBluetoothSettings()
    }

    /** Lists devices this phone has paired with, or goes straight to pairing when there are none. */
    fun showDevicePicker() {
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
            row.icon.setImageResource(s.typeOf(device).iconRes)
            val isCurrent = device == current
            row.lastUsed.isVisible = isCurrent || (current == null && device.address == prefs.lastHost)
            row.lastUsed.setText(if (isCurrent) R.string.status_connected else R.string.last_used)
            row.root.setOnClickListener {
                dialog.dismiss()
                if (!isCurrent) connectTo(device)
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

    /** Connects to [device]. Exposed for the Devices tab. */
    fun connectTo(device: BluetoothDevice) {
        service?.connect(device)
        refreshStatus()
    }

    /** Makes the phone visible, then shows where to look on the device. */
    fun startPairing() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
        // Launching this without the advertise permission is what used to
        // crash Pair on Android 12+. The step machine only offers pairing
        // once permissions are granted, and some ROMs have no such screen at
        // all, so failure falls back to the steps alone: many TVs still find
        // the phone while its Bluetooth settings are open.
        if (!hasBluetoothPermissions()) {
            requestPermissions()
            return
        }
        AppOpenAds.skipNextReturn()
        val launched = runCatching { discoverable.launch(intent) }.isSuccess
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
        AppOpenAds.skipNextReturn()
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun openBluetoothSettings() {
        AppOpenAds.skipNextReturn()
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    /**
     * Whether an app open ad may cover this screen on return. Not before the
     * screen exists, not over the snippets vault, and not while a connection
     * is being made.
     */
    fun allowsAppOpenAd(): Boolean =
        ::ui.isInitialized &&
            currentTab != R.id.tab_snippets &&
            service?.state != HidService.State.CONNECTING

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
        service?.removeListener(stateListener)
        service?.removeNoticeListener(noticeListener)
        if (bound) runCatching { unbindService(connection) }
    }

    private companion object {
        const val PRO_NUDGE_DELAY_MS = 1600L
        const val DISCOVERABLE_SECONDS = 180
        const val DAY_MS = 86_400_000L
        const val KEY_LAST_NUDGE = "last_pro_nudge_day"
        const val KEY_TAB = "tab"

        /** Material's medium window class starts at 600dp. */
        const val RAIL_MIN_WIDTH_DP = 600

        /** Below this, a header row costs height the controls need. */
        const val COMPACT_HEIGHT_DP = 480
    }
}
