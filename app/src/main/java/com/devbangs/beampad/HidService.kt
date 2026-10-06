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
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class HidService : Service() {

    inner class LocalBinder : Binder() {
        val service: HidService get() = this@HidService
    }

    /** Where the connection stands. The home screen renders straight from this. */
    enum class State {
        /** No Bluetooth hardware at all. */
        NO_BLUETOOTH,
        BLUETOOTH_OFF,

        /** Registering the keyboard with the Bluetooth stack. */
        STARTING,

        /** The phone does not offer keyboard mode, or refused to register it. */
        UNSUPPORTED,

        /** Registered and waiting for a host. */
        READY,
        CONNECTING,
        CONNECTED
    }

    /** One-off events the screen reports once rather than renders. */
    enum class Notice { CONNECT_FAILED }

    /** Rough kind of host, for the icon in the device list. */
    enum class HostKind { TV, COMPUTER, PHONE }

    private val binder = LocalBinder()

    /** Reports go out on one thread so keys arrive in order. */
    private val exec = Executors.newSingleThreadExecutor()

    /** Stack callbacks on their own thread: a long snippet must not delay a disconnect. */
    private val callbackExec = Executors.newSingleThreadExecutor()

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var hid: BluetoothHidDevice? = null
    private var adapter: BluetoothAdapter? = null
    @Volatile private var registered = false

    /** A connect asked for before registration finished. */
    @Volatile private var pendingConnect: BluetoothDevice? = null

    /** Auto-reconnect runs once per start, and never after the user acted. */
    @Volatile private var autoReconnectDone = false

    /** Automatic attempts fail quietly: the screen already offers Connect. */
    @Volatile private var quietAttempt = false

    @Volatile var state: State = State.STARTING
        private set

    @Volatile var connectedDevice: BluetoothDevice? = null
        private set

    /** The host a connection is in progress with, for "Connecting to…". */
    @Volatile var connectingDevice: BluetoothDevice? = null
        private set

    private val prefs by lazy { Prefs(this) }

    /** Layout of the receiving device. Persisted across restarts. */
    var layout: HidReports.Layout
        get() = prefs.layout
        set(value) {
            prefs.layout = value
        }

    /** Called on the main thread whenever [state] changes. */
    var listener: (() -> Unit)? = null

    /** Called on the main thread for one-off events. */
    var onNotice: ((Notice) -> Unit)? = null

    private fun setState(next: State) {
        state = next
        main.post {
            listener?.invoke()
            updateNotification()
        }
    }

    private fun notice(n: Notice) {
        if (quietAttempt) return
        main.post { onNotice?.invoke(n) }
    }

    private fun bluetoothOn(): Boolean =
        runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> acquireProxy()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    releaseProxy()
                    setState(State.BLUETOOTH_OFF)
                }
            }
        }
    }

    private val registrationTimeout = Runnable {
        // Some phones hand out the profile but never confirm registration.
        // Saying so beats an endless "Starting".
        if (!registered && state == State.STARTING) setState(State.UNSUPPORTED)
    }

    private val connectTimeout = Runnable {
        if (connectingDevice == null || connectedDevice != null) return@Runnable
        connectingDevice = null
        setState(if (registered) State.READY else State.STARTING)
        notice(Notice.CONNECT_FAILED)
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidService.registered = registered
            main.removeCallbacks(registrationTimeout)

            if (!registered) {
                connectedDevice = null
                connectingDevice = null
                setState(if (bluetoothOn()) State.STARTING else State.BLUETOOTH_OFF)
                return
            }

            if (connectedDevice == null && connectingDevice == null) setState(State.READY)

            val requested = pendingConnect
            pendingConnect = null
            if (requested != null) {
                connectNow(requested, quiet = false)
            } else {
                autoReconnectTarget(pluggedDevice)?.let { connectNow(it, quiet = true) }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    main.removeCallbacks(connectTimeout)
                    connectedDevice = device
                    connectingDevice = null
                    quietAttempt = false
                    sentThisSession = false
                    prefs.lastHost = device.address
                    setState(State.CONNECTED)
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    if (connectedDevice == null) {
                        connectingDevice = device
                        setState(State.CONNECTING)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val failedAttempt = device == connectingDevice
                    if (failedAttempt) connectingDevice = null
                    if (device == connectedDevice) connectedDevice = null

                    when {
                        connectedDevice != null -> Unit
                        // Switching hosts: the old one dropping is expected.
                        connectingDevice != null -> setState(State.CONNECTING)
                        else -> {
                            main.removeCallbacks(connectTimeout)
                            setState(if (registered) State.READY else State.STARTING)
                            if (failedAttempt) notice(Notice.CONNECT_FAILED)
                            quietAttempt = false
                        }
                    }
                }
            }
        }
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
                setState(State.UNSUPPORTED)
                return
            }
            main.removeCallbacks(registrationTimeout)
            main.postDelayed(registrationTimeout, REGISTER_TIMEOUT_MS)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            registered = false
            connectedDevice = null
            connectingDevice = null
            setState(if (bluetoothOn()) State.STARTING else State.BLUETOOTH_OFF)
        }
    }

    override fun onCreate() {
        super.onCreate()
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
        if (!ok) setState(State.UNSUPPORTED)
    }

    private fun releaseProxy() {
        main.removeCallbacks(registrationTimeout)
        main.removeCallbacks(connectTimeout)
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
        releaseProxy()
        acquireProxy()
    }

    /**
     * Connects to a host this phone has paired with before. If registration
     * is still in progress the request waits for it instead of failing.
     */
    fun connect(device: BluetoothDevice) {
        autoReconnectDone = true
        if (device == connectedDevice) return
        if (!registered || hid == null) {
            pendingConnect = device
            return
        }
        connectNow(device, quiet = false)
    }

    private fun connectNow(device: BluetoothDevice, quiet: Boolean) {
        val h = hid
        if (h == null) {
            pendingConnect = device
            return
        }
        quietAttempt = quiet

        // The profile allows one host at a time.
        connectedDevice?.let { current -> runCatching { h.disconnect(current) } }

        connectingDevice = device
        setState(State.CONNECTING)
        val ok = runCatching { h.connect(device) }.getOrDefault(false)
        if (!ok) {
            connectingDevice = null
            setState(if (connectedDevice != null) State.CONNECTED else State.READY)
            notice(Notice.CONNECT_FAILED)
            quietAttempt = false
            return
        }
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun autoReconnectTarget(plugged: BluetoothDevice?): BluetoothDevice? {
        if (autoReconnectDone || !prefs.autoReconnect) return null
        autoReconnectDone = true
        val hosts = pairedHosts()
        plugged?.let { p -> hosts.firstOrNull { it == p }?.let { return it } }
        val address = prefs.lastHost ?: return null
        return hosts.firstOrNull { it.address == address }
    }

    /** Devices bonded with this phone that can take keyboard input, last used first. */
    fun pairedHosts(): List<BluetoothDevice> {
        val bonded = runCatching {
            adapter?.bondedDevices?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val last = prefs.lastHost
        return bonded
            .filter { canHost(it) }
            .sortedWith(
                compareByDescending<BluetoothDevice> { it.address == last }
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

    fun hostKind(device: BluetoothDevice): HostKind {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        return when (major) {
            BluetoothClass.Device.Major.COMPUTER -> HostKind.COMPUTER
            BluetoothClass.Device.Major.PHONE -> HostKind.PHONE
            else -> HostKind.TV
        }
    }

    fun lastHostName(): String? {
        val address = prefs.lastHost ?: return null
        return pairedHosts().firstOrNull { it.address == address }?.let { deviceLabel(it) }
    }

    private fun send(device: BluetoothDevice, id: Int, report: ByteArray): Boolean {
        val h = hid ?: return false
        return runCatching { h.sendReport(device, id, report) }.getOrDefault(false)
    }

    /** Sends a key press followed by a release. Call off the main thread. */
    fun tapKey(modifier: Byte, keyCode: Byte): Boolean {
        val dev = connectedDevice ?: return false
        sentThisSession = true
        if (!send(dev, HidReports.REPORT_ID, HidReports.press(modifier, keyCode))) return false
        Thread.sleep(KEY_DELAY_MS)
        send(dev, HidReports.REPORT_ID, HidReports.release())
        Thread.sleep(KEY_DELAY_MS)
        return true
    }

    /**
     * Types a whole string on the send executor, one key at a time.
     * [done] reports how many characters were sent and how many had no mapping.
     */
    fun typeText(text: String, done: (sent: Int, skipped: Int) -> Unit) {
        exec.execute {
            var sent = 0
            var skipped = 0
            for (c in text) {
                val enc = HidReports.encode(c, layout)
                if (enc == null) {
                    skipped++
                    continue
                }
                if (!tapKey(enc.first, enc.second)) break
                sent++
            }
            done(sent, skipped)
        }
    }

    /** Relative pointer movement. Safe to call at touch-event rate. */
    fun moveMouse(dx: Int, dy: Int) {
        val dev = connectedDevice ?: return
        send(dev, HidReports.REPORT_ID_MOUSE, HidReports.mouse(HidReports.BUTTON_NONE, dx, dy))
    }

    fun scroll(amount: Int) {
        val dev = connectedDevice ?: return
        send(dev, HidReports.REPORT_ID_MOUSE, HidReports.mouse(HidReports.BUTTON_NONE, 0, 0, amount))
    }

    /** Press and release a mouse button in place. */
    fun click(button: Byte) {
        val dev = connectedDevice ?: return
        exec.execute {
            send(dev, HidReports.REPORT_ID_MOUSE, HidReports.mouse(button, 0, 0))
            Thread.sleep(KEY_DELAY_MS)
            send(dev, HidReports.REPORT_ID_MOUSE, HidReports.mouse(HidReports.BUTTON_NONE, 0, 0))
        }
    }

    /**
     * Sends a consumer control code: volume, mute, play/pause.
     * A release report must follow or the receiver treats the key as held.
     */
    fun consumerKey(usage: Int) {
        val dev = connectedDevice ?: return
        exec.execute {
            send(dev, HidReports.REPORT_ID_CONSUMER, HidReports.consumer(usage))
            Thread.sleep(KEY_DELAY_MS)
            send(dev, HidReports.REPORT_ID_CONSUMER, HidReports.consumerRelease())
        }
    }

    /** Sends a single key, optionally with a modifier held, off the main thread. */
    fun typeKey(modifier: Byte, keyCode: Byte) {
        exec.execute { tapKey(modifier, keyCode) }
    }

    /**
     * Drops the current connection. The HID app stays registered, so the
     * device can pair again without reopening the app.
     */
    fun disconnect(): Boolean {
        autoReconnectDone = true
        val dev = connectedDevice ?: return false
        val h = hid ?: return false
        return runCatching { h.disconnect(dev) }.getOrDefault(false)
    }

    /** True once anything has actually been sent this connection. */
    var sentThisSession: Boolean = false
        private set

    fun isReady(): Boolean = hid != null && connectedDevice != null

    fun deviceLabel(device: BluetoothDevice): String =
        runCatching { device.name ?: device.address }.getOrNull() ?: getString(R.string.device_fallback)

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
        exec.shutdown()
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
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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
        /** Gap between reports. Slower stacks drop keys sent back-to-back. */
        private const val KEY_DELAY_MS = 12L
        private const val REGISTER_TIMEOUT_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CHANNEL_ID = "beampad_connection"
        private const val NOTIF_ID = 1
        const val ACTION_DISCONNECT = "com.devbangs.beampad.DISCONNECT"

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
