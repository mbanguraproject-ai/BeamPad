package com.devbangs.beampad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * The connection engine. Owns the Bluetooth HID registration and the link
 * to one host, independent of any screen, and hands report sending to
 * [engine]. Every screen reads [state] and observes changes through
 * [addListener], so they all show the same thing.
 */
class HidService : Service() {

    inner class LocalBinder : Binder() {
        val service: HidService get() = this@HidService
    }

    /**
     * Where the connection stands. Maps onto the blueprint's visible states:
     * CONNECTED, CONNECTING, READY ("available"), DISCONNECTED (a link that
     * dropped), and the action-required states BLUETOOTH_OFF, UNSUPPORTED
     * and NO_BLUETOOTH.
     */
    enum class State {
        NO_BLUETOOTH,
        BLUETOOTH_OFF,

        /** Registering the keyboard with the Bluetooth stack. */
        STARTING,

        /** Keyboard mode cannot run; see [unsupportedReason]. */
        UNSUPPORTED,

        /** Registered and waiting for a host. */
        READY,
        CONNECTING,
        CONNECTED,

        /** A connection dropped without the user asking; see [lostDevice]. */
        DISCONNECTED
    }

    /** Why keyboard mode is unavailable. Each has its own explanation and fix. */
    enum class UnsupportedReason {
        /** The phone's Bluetooth has no HID Device profile at all. */
        NO_PROFILE,

        /** The profile exists but refused to register, usually another app holding it. */
        REFUSED,

        /** Registration never answered. */
        NO_RESPONSE
    }

    /** One-off events, reported once rather than rendered. */
    enum class Notice { CONNECTED, CONNECT_FAILED, LOST, GAVE_UP_RETRYING, SEND_FAILED }

    private val binder = LocalBinder()

    /** Stack callbacks on their own thread: a long snippet must not delay a disconnect. */
    private val callbackExec = Executors.newSingleThreadExecutor()

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var hid: BluetoothHidDevice? = null
    private var adapter: BluetoothAdapter? = null
    @Volatile private var registered = false

    /** A connect asked for before registration finished. */
    @Volatile private var pendingConnect: BluetoothDevice? = null

    /** Automatic attempts fail quietly: the screen already offers Connect. */
    @Volatile private var quietAttempt = false

    /** Set when the user disconnects, so nothing reconnects behind their back. */
    @Volatile private var userParked = false
    @Volatile private var userDisconnecting = false

    @Volatile var state: State = State.STARTING
        private set

    @Volatile var unsupportedReason: UnsupportedReason? = null
        private set

    @Volatile var connectedDevice: BluetoothDevice? = null
        private set

    /** The host a connection is in progress with, for "Connecting to…". */
    @Volatile var connectingDevice: BluetoothDevice? = null
        private set

    /** The host whose connection dropped, offered for one-tap reconnect. */
    @Volatile var lostDevice: BluetoothDevice? = null
        private set

    /** Automatic reconnect attempts made since the last drop; 0 when not retrying. */
    @Volatile var retryAttempt = 0
        private set

    val isRetrying: Boolean get() = lostDevice != null && retryAttempt < RETRY_DELAYS_MS.size &&
        prefs.retryAfterDrop && state == State.DISCONNECTED

    private val prefs by lazy { Prefs(this) }
    private val devices by lazy { DeviceStore(this) }

    /** Per-device layout from the connected device's profile, when Pro. */
    @Volatile private var deviceLayout: HidReports.Layout? = null

    /** Layout of the receiving device: its profile's, else the app setting. */
    var layout: HidReports.Layout
        get() = deviceLayout ?: prefs.layout
        set(value) {
            prefs.layout = value
        }

    /** Every control in the app sends through this. */
    lateinit var engine: InputEngine
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val noticeListeners = CopyOnWriteArraySet<(Notice) -> Unit>()

    /** Called on the main thread whenever [state] or its details change. */
    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /** Called on the main thread for one-off events. */
    fun addNoticeListener(l: (Notice) -> Unit) {
        noticeListeners += l
    }

    fun removeNoticeListener(l: (Notice) -> Unit) {
        noticeListeners -= l
    }

    private fun setState(next: State) {
        state = next
        if (next != State.UNSUPPORTED) unsupportedReason = null
        main.post {
            listeners.forEach { it() }
            updateNotification()
        }
    }

    private fun notice(n: Notice) {
        if (quietAttempt && n == Notice.CONNECT_FAILED) return
        main.post { noticeListeners.forEach { it(n) } }
    }

    private fun log(kind: ConnectionLog.Kind, detail: String = "") =
        ConnectionLog.add(this, kind, detail)

    private fun bluetoothOn(): Boolean =
        runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    log(ConnectionLog.Kind.BLUETOOTH, "on")
                    acquireProxy()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (state != State.BLUETOOTH_OFF) log(ConnectionLog.Kind.BLUETOOTH, "off")
                    // A device connected when Bluetooth went off is offered back
                    // when it returns, rather than forgotten.
                    connectedDevice?.let { lostDevice = it }
                    main.removeCallbacks(retryRunnable)
                    releaseProxy()
                    setState(State.BLUETOOTH_OFF)
                }
            }
        }
    }

    /**
     * Android can withdraw the keyboard registration after startup: another
     * app took the Bluetooth keyboard role, or the Bluetooth stack
     * restarted. Register again a few times, then say so (with Try again)
     * instead of showing "Getting ready" for ever.
     */
    private var reRegisterAttempt = 0

    private val reRegister = Runnable {
        if (registered || !bluetoothOn()) return@Runnable
        log(ConnectionLog.Kind.RETRY, "register again, attempt $reRegisterAttempt")
        releaseProxy()
        acquireProxy()
    }

    private fun scheduleReRegister(why: String) {
        main.removeCallbacks(reRegister)
        if (reRegisterAttempt >= REREGISTER_DELAYS_MS.size) {
            markUnsupported(UnsupportedReason.NO_RESPONSE)
            return
        }
        log(ConnectionLog.Kind.LOST, "registration $why")
        main.postDelayed(reRegister, REREGISTER_DELAYS_MS[reRegisterAttempt++])
    }

    private val registrationTimeout = Runnable {
        // Some phones hand out the profile but never confirm registration.
        // Saying so beats an endless "Starting".
        if (!registered && state == State.STARTING) markUnsupported(UnsupportedReason.NO_RESPONSE)
    }

    private val connectTimeout = Runnable {
        val target = connectingDevice
        if (target == null || connectedDevice != null) return@Runnable
        connectingDevice = null
        log(ConnectionLog.Kind.FAILED, "timeout ${label(target)}")
        attemptFailed()
    }

    private val retryRunnable = Runnable {
        val target = lostDevice ?: return@Runnable
        if (!registered || !bluetoothOn() || connectedDevice != null) return@Runnable
        retryAttempt++
        log(ConnectionLog.Kind.RETRY, "attempt $retryAttempt ${label(target)}")
        connectNow(target, quiet = true)
    }

    private fun markUnsupported(reason: UnsupportedReason) {
        log(ConnectionLog.Kind.UNSUPPORTED, reason.name)
        setState(State.UNSUPPORTED)
        unsupportedReason = reason
        main.post { listeners.forEach { it() } }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            val wasRegistered = this@HidService.registered
            this@HidService.registered = registered
            main.removeCallbacks(registrationTimeout)

            if (!registered) {
                // A deliberate release clears hid and registered first, so
                // only a registration Android withdrew gets here with both set.
                val dropped = wasRegistered && hid != null
                if (dropped) connectedDevice?.let { lostDevice = it }
                connectedDevice = null
                connectingDevice = null
                setState(if (bluetoothOn()) State.STARTING else State.BLUETOOTH_OFF)
                if (dropped && bluetoothOn()) main.post { scheduleReRegister("withdrawn") }
                return
            }
            reRegisterAttempt = 0
            main.removeCallbacks(reRegister)
            log(ConnectionLog.Kind.REGISTERED)

            if (connectedDevice == null && connectingDevice == null) {
                setState(if (lostDevice != null) State.DISCONNECTED else State.READY)
            }

            val requested = pendingConnect
            pendingConnect = null
            when {
                requested != null -> connectNow(requested, quiet = false)
                userParked || !prefs.autoReconnect -> Unit
                lostDevice != null -> lostDevice?.let { connectNow(it, quiet = true) }
                else -> autoReconnectTarget(pluggedDevice)?.let { connectNow(it, quiet = true) }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> onConnected(device)

                BluetoothProfile.STATE_CONNECTING -> {
                    if (connectedDevice == null) {
                        connectingDevice = device
                        setState(State.CONNECTING)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> onDisconnected(device)
            }
        }
    }

    private fun onConnected(device: BluetoothDevice) {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(retryRunnable)
        connectedDevice = device
        connectingDevice = null
        lostDevice = null
        retryAttempt = 0
        quietAttempt = false
        userParked = false
        userDisconnecting = false
        engine.resetSession()
        prefs.lastHost = device.address
        val saved = devices.recordConnected(device.address, systemName(device), guessType(device))
        deviceLayout = if (Features.profiles(this)) saved.layout else null
        log(ConnectionLog.Kind.CONNECTED, saved.displayName)
        setState(State.CONNECTED)
        notice(Notice.CONNECTED)
    }

    private fun onDisconnected(device: BluetoothDevice) {
        val failedAttempt = device == connectingDevice
        val wasConnected = device == connectedDevice
        if (failedAttempt) connectingDevice = null
        if (wasConnected) {
            connectedDevice = null
            engine.cancelAll()
        }

        when {
            connectedDevice != null -> Unit
            // Switching hosts: the old one dropping is expected.
            connectingDevice != null -> setState(State.CONNECTING)
            wasConnected && userDisconnecting -> {
                userDisconnecting = false
                log(ConnectionLog.Kind.DISCONNECTED, label(device))
                setState(if (registered) State.READY else State.STARTING)
            }
            wasConnected -> {
                // Dropped without being asked: the TV slept, rebooted or went
                // out of range. Remember it and try again quietly.
                lostDevice = device
                retryAttempt = 0
                log(ConnectionLog.Kind.LOST, label(device))
                setState(State.DISCONNECTED)
                notice(Notice.LOST)
                scheduleRetry()
            }
            failedAttempt -> {
                log(ConnectionLog.Kind.FAILED, label(device))
                attemptFailed()
            }
            else -> setState(if (lostDevice != null) State.DISCONNECTED else if (registered) State.READY else State.STARTING)
        }
    }

    /** A connection attempt ended without connecting. */
    private fun attemptFailed() {
        main.removeCallbacks(connectTimeout)
        val retrying = lostDevice != null
        setState(if (retrying) State.DISCONNECTED else if (registered) State.READY else State.STARTING)
        notice(Notice.CONNECT_FAILED)
        quietAttempt = false
        if (retrying) scheduleRetry()
    }

    private fun scheduleRetry() {
        main.removeCallbacks(retryRunnable)
        if (lostDevice == null || userParked || !prefs.retryAfterDrop) return
        if (retryAttempt >= RETRY_DELAYS_MS.size) {
            notice(Notice.GAVE_UP_RETRYING)
            main.post { listeners.forEach { it() } }
            return
        }
        main.postDelayed(retryRunnable, RETRY_DELAYS_MS[retryAttempt])
        main.post { listeners.forEach { it() } }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val device = proxy as BluetoothHidDevice
            hid = device
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "BeamPad",
                "Phone as keyboard and mouse",
                "Dev_Bangs",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidReports.KEYBOARD_DESCRIPTOR
            )
            val ok = runCatching {
                device.registerApp(sdp, null, null, callbackExec, hidCallback)
            }.getOrDefault(false)

            if (!ok) {
                markUnsupported(UnsupportedReason.REFUSED)
                return
            }
            main.removeCallbacks(registrationTimeout)
            main.postDelayed(registrationTimeout, REGISTER_TIMEOUT_MS)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            registered = false
            connectedDevice?.let { lostDevice = it }
            connectedDevice = null
            connectingDevice = null
            setState(if (bluetoothOn()) State.STARTING else State.BLUETOOTH_OFF)
            if (bluetoothOn()) scheduleReRegister("profile service lost")
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = InputEngine(
            sink = { id, report -> send(id, report) },
            layout = { layout },
            findMacro = { id -> MacroStore(this).get(id) }
        )
        createChannel()
        startForegroundSafely()
        adapter = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull()
        ContextCompat.registerReceiver(
            this,
            btStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        acquireProxy()
    }

    private fun acquireProxy() {
        val a = adapter
        if (a == null) {
            setState(State.NO_BLUETOOTH)
            return
        }
        if (!bluetoothOn()) {
            setState(State.BLUETOOTH_OFF)
            return
        }
        if (hid != null) return

        setState(State.STARTING)
        val ok = runCatching {
            a.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE)
        }.getOrDefault(false)
        if (!ok) {
            markUnsupported(UnsupportedReason.NO_PROFILE)
            return
        }
        // Covers a profile service that never answers, as well as a
        // registration that is never confirmed (re-armed when it answers).
        main.removeCallbacks(registrationTimeout)
        main.postDelayed(registrationTimeout, REGISTER_TIMEOUT_MS)
    }

    private fun releaseProxy() {
        main.removeCallbacks(registrationTimeout)
        main.removeCallbacks(connectTimeout)
        engine.cancelAll()
        val h = hid ?: return
        hid = null
        registered = false
        connectedDevice = null
        connectingDevice = null
        runCatching { h.unregisterApp() }
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
    }

    /** Drops the profile and registers again. Behind the Try again button. */
    fun restart() {
        reRegisterAttempt = 0
        main.removeCallbacks(reRegister)
        releaseProxy()
        acquireProxy()
    }

    /**
     * Connects to a host this phone has paired with before. If registration
     * is still in progress the request waits for it instead of failing.
     */
    fun connect(device: BluetoothDevice) {
        userParked = false
        main.removeCallbacks(retryRunnable)
        if (device != lostDevice) {
            lostDevice = null
            retryAttempt = 0
        }
        if (device == connectedDevice) return
        if (!registered || hid == null) {
            pendingConnect = device
            return
        }
        connectNow(device, quiet = false)
    }

    /** One tap back to the device that dropped, or else the last one used. */
    fun reconnect(): Boolean {
        val target = lostDevice ?: lastHost() ?: return false
        retryAttempt = 0
        connect(target)
        return true
    }

    private fun lastHost(): BluetoothDevice? {
        val address = prefs.lastHost ?: return null
        return pairedHosts().firstOrNull { it.address == address }
    }

    private fun connectNow(device: BluetoothDevice, quiet: Boolean) {
        val h = hid
        if (h == null) {
            pendingConnect = device
            return
        }
        quietAttempt = quiet

        // The profile allows one host at a time.
        connectedDevice?.let { current ->
            userDisconnecting = true
            runCatching { h.disconnect(current) }
        }

        connectingDevice = device
        log(ConnectionLog.Kind.CONNECTING, label(device))
        setState(State.CONNECTING)
        val ok = runCatching { h.connect(device) }.getOrDefault(false)
        if (!ok) {
            connectingDevice = null
            log(ConnectionLog.Kind.FAILED, "refused ${label(device)}")
            if (connectedDevice != null) setState(State.CONNECTED) else attemptFailed()
            return
        }
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, prefs.connectTimeout.millis)
    }

    private fun autoReconnectTarget(plugged: BluetoothDevice?): BluetoothDevice? {
        val hosts = pairedHosts()
        plugged?.let { p -> hosts.firstOrNull { it == p }?.let { return it } }
        val address = prefs.lastHost ?: return null
        return hosts.firstOrNull { it.address == address }
    }

    /** Devices bonded with this phone that can take keyboard input, most recent first. */
    fun pairedHosts(): List<BluetoothDevice> {
        val bonded = runCatching {
            adapter?.bondedDevices?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val recency = devices.all().withIndex().associate { (i, d) -> d.address to i }
        return bonded
            .filter { canHost(it) }
            .sortedWith(
                compareBy<BluetoothDevice> { recency[it.address] ?: Int.MAX_VALUE }
                    .thenBy { deviceLabel(it).lowercase() }
            )
    }

    /** Headphones, speakers, watches and other keyboards can never be a host. */
    private fun canHost(device: BluetoothDevice): Boolean {
        val cls = runCatching { device.bluetoothClass }.getOrNull() ?: return true
        return when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.WEARABLE,
            BluetoothClass.Device.Major.HEALTH,
            BluetoothClass.Device.Major.TOY,
            BluetoothClass.Device.Major.PERIPHERAL -> false
            BluetoothClass.Device.Major.AUDIO_VIDEO -> cls.deviceClass !in AUDIO_ONLY
            else -> true
        }
    }

    /** The saved type when the user set one, else a guess from the Bluetooth class. */
    fun typeOf(device: BluetoothDevice): DeviceType =
        devices.get(device.address)?.type ?: guessType(device)

    private fun guessType(device: BluetoothDevice): DeviceType {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        return when (major) {
            BluetoothClass.Device.Major.COMPUTER -> DeviceType.COMPUTER
            BluetoothClass.Device.Major.PHONE -> DeviceType.PHONE
            else -> DeviceType.TV
        }
    }

    fun lastHostName(): String? {
        val address = prefs.lastHost ?: return null
        return devices.get(address)?.displayName
            ?: pairedHosts().firstOrNull { it.address == address }?.let { deviceLabel(it) }
    }

    /** Re-reads the connected device's profile after it was edited. */
    fun refreshProfile() {
        val address = connectedDevice?.address ?: return
        deviceLayout = if (Features.profiles(this)) devices.get(address)?.layout else null
        main.post { listeners.forEach { it() } }
    }

    private fun send(id: Int, report: ByteArray): Boolean {
        val h = hid ?: return false
        val dev = connectedDevice ?: return false
        val ok = runCatching { h.sendReport(dev, id, report) }.getOrDefault(false)
        if (!ok) {
            log(ConnectionLog.Kind.SEND_FAILED, "report $id")
            // Connected but refused: say so (once in a while), rather than
            // leave a key press that silently did nothing.
            val now = SystemClock.elapsedRealtime()
            if (now - lastSendFailedNotice > SEND_FAILED_NOTICE_GAP_MS) {
                lastSendFailedNotice = now
                notice(Notice.SEND_FAILED)
            }
        }
        return ok
    }

    @Volatile private var lastSendFailedNotice = 0L

    // Thin wrappers kept for screens written against the earlier API.

    fun typeText(text: String, done: (sent: Int, skipped: Int) -> Unit) {
        engine.keyboard.type(text) { r -> done(r.sent, r.skipped) }
    }

    fun typeKey(modifier: Byte, keyCode: Byte) = engine.keyboard.tap(keyCode.toInt(), modifier.toInt())

    fun consumerKey(usage: Int) = engine.consumer.press(usage)

    fun moveMouse(dx: Int, dy: Int) = engine.mouse.move(dx, dy)

    fun scroll(amount: Int) = engine.mouse.scroll(amount)

    fun click(button: Byte) = engine.mouse.click(button.toInt())

    /**
     * Drops the current connection. The HID app stays registered, so the
     * device can pair again without reopening the app.
     */
    fun disconnect(): Boolean {
        userParked = true
        main.removeCallbacks(retryRunnable)
        lostDevice = null
        retryAttempt = 0
        val dev = connectedDevice ?: run {
            setState(if (registered) State.READY else State.STARTING)
            return false
        }
        val h = hid ?: return false
        userDisconnecting = true
        return runCatching { h.disconnect(dev) }.getOrDefault(false)
    }

    /** Stops trying to bring back a dropped connection. */
    fun forgetLost() {
        main.removeCallbacks(retryRunnable)
        lostDevice = null
        retryAttempt = 0
        setState(if (registered) State.READY else State.STARTING)
    }

    /** True once anything has actually been sent this connection. */
    val sentThisSession: Boolean get() = engine.sentSomething

    fun isReady(): Boolean = hid != null && connectedDevice != null

    /** The user's name for a device when they set one, else its Bluetooth name. */
    fun deviceLabel(device: BluetoothDevice): String =
        devices.get(device.address)?.displayName ?: systemName(device)

    private fun systemName(device: BluetoothDevice): String =
        runCatching { device.name ?: device.address }.getOrNull() ?: getString(R.string.device_fallback)

    private fun label(device: BluetoothDevice): String = deviceLabel(device)

    fun localBluetoothName(): String =
        runCatching { adapter?.name }.getOrNull() ?: getString(R.string.this_phone)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) disconnect()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(btStateReceiver) }
        releaseProxy()
        engine.shutdown()
        callbackExec.shutdown()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun statusText(): String = when (state) {
        State.CONNECTED -> getString(
            R.string.status_connected_to,
            connectedDevice?.let { deviceLabel(it) } ?: getString(R.string.device_fallback)
        )
        State.CONNECTING -> getString(R.string.status_connecting_short)
        State.READY -> getString(R.string.status_ready)
        State.STARTING -> getString(R.string.status_starting)
        State.BLUETOOTH_OFF -> getString(R.string.status_bt_off)
        State.UNSUPPORTED -> getString(R.string.status_unsupported)
        State.NO_BLUETOOTH -> getString(R.string.status_no_bluetooth)
        State.DISCONNECTED -> getString(
            R.string.status_lost_to,
            lostDevice?.let { deviceLabel(it) } ?: getString(R.string.device_fallback)
        )
    }

    private fun buildNotification(): Notification {
        // Single top: tapping the notification returns to the open screen
        // rather than stacking a second copy of it.
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText())
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (state == State.CONNECTED) {
            val disconnect = PendingIntent.getService(
                this, 1,
                Intent(this, HidService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_x),
                    getString(R.string.action_disconnect),
                    disconnect
                ).build()
            )
        }
        return builder.build()
    }

    /**
     * Android 14 refuses a connected-device foreground service without the
     * Bluetooth permission, and throws. That used to crash the app when the
     * system restarted the service after the permission was taken away.
     * Without the foreground slot the service still works while bound, and
     * stopSelf clears the start so the system does not time it out.
     */
    private fun startForegroundSafely() {
        val ok = runCatching {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }.isSuccess
        if (!ok) stopSelf()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    companion object {
        private const val REGISTER_TIMEOUT_MS = 8_000L
        private val REREGISTER_DELAYS_MS = longArrayOf(1_000, 3_000, 10_000)
        private const val SEND_FAILED_NOTICE_GAP_MS = 4_000L
        private const val CHANNEL_ID = "beampad_connection"
        private const val NOTIF_ID = 1
        const val ACTION_DISCONNECT = "com.devbangs.beampad.DISCONNECT"

        /** Backoff for quietly retrying a dropped connection: TVs take a while to wake. */
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000)

        private val AUDIO_ONLY = setOf(
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO
        )
    }
}
