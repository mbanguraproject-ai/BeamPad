package com.devbangs.beampad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class HidService : Service() {

    inner class LocalBinder : Binder() {
        val service: HidService get() = this@HidService
    }

    private val binder = LocalBinder()
    private val exec = Executors.newSingleThreadExecutor()

    private var hid: BluetoothHidDevice? = null
    private var adapter: BluetoothAdapter? = null

    var connectedDevice: BluetoothDevice? = null
        private set

    private val prefs by lazy {
        getSharedPreferences("beampad", Context.MODE_PRIVATE)
    }

    /** Layout of the receiving device. Persisted across restarts. */
    var layout: HidReports.Layout
        get() = runCatching {
            HidReports.Layout.valueOf(
                prefs.getString(KEY_LAYOUT, null) ?: HidReports.Layout.US.name
            )
        }.getOrDefault(HidReports.Layout.US)
        set(value) {
            prefs.edit().putString(KEY_LAYOUT, value.name).apply()
        }

    /** Set by the Activity to receive status lines and state changes. */
    var listener: ((String) -> Unit)? = null

    private fun report(msg: String) {
        listener?.invoke(msg)
        updateNotification(msg)
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (state == BluetoothAdapter.STATE_ON) {
                report("bluetooth on - registering")
                acquireProxy()
            } else if (state == BluetoothAdapter.STATE_OFF) {
                connectedDevice = null
                hid = null
                report("bluetooth off")
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(plugged: BluetoothDevice?, registered: Boolean) {
            report(if (registered) "registered - ready to pair" else "unregistered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    report("connected: ${deviceLabel(device)}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == connectedDevice) connectedDevice = null
                    report("disconnected")
                }
                BluetoothProfile.STATE_CONNECTING -> report("connecting...")
                BluetoothProfile.STATE_DISCONNECTING -> report("disconnecting...")
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "BeamPad",
                "Phone as keyboard and mouse",
                "Dev_Bangs",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidReports.KEYBOARD_DESCRIPTOR
            )
            runCatching { hid?.registerApp(sdp, null, null, exec, hidCallback) }
                .onFailure { report("registerApp failed: ${it.message}") }
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            connectedDevice = null
            report("hid service lost")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
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
        if (a == null) { report("no bluetooth adapter"); return }
        if (!a.isEnabled) { report("bluetooth is off"); return }
        val ok = a.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE)
        if (!ok) report("HID Device profile unavailable on this phone")
    }

    /** Sends a key press followed by a release. Call off the main thread. */
    fun tapKey(modifier: Byte, keyCode: Byte): Boolean {
        val dev = connectedDevice ?: return false
        val h = hid ?: return false
        h.sendReport(dev, HidReports.REPORT_ID, HidReports.press(modifier, keyCode))
        Thread.sleep(KEY_DELAY_MS)
        h.sendReport(dev, HidReports.REPORT_ID, HidReports.release())
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
                if (enc == null) { skipped++; continue }
                if (!tapKey(enc.first, enc.second)) break
                sent++
            }
            done(sent, skipped)
        }
    }

    /** Relative pointer movement. Safe to call at touch-event rate. */
    fun moveMouse(dx: Int, dy: Int) {
        val dev = connectedDevice ?: return
        val h = hid ?: return
        h.sendReport(
            dev, HidReports.REPORT_ID_MOUSE,
            HidReports.mouse(HidReports.BUTTON_NONE, dx, dy)
        )
    }

    fun scroll(amount: Int) {
        val dev = connectedDevice ?: return
        val h = hid ?: return
        h.sendReport(
            dev, HidReports.REPORT_ID_MOUSE,
            HidReports.mouse(HidReports.BUTTON_NONE, 0, 0, amount)
        )
    }

    /** Press and release a mouse button in place. */
    fun click(button: Byte) {
        val dev = connectedDevice ?: return
        val h = hid ?: return
        exec.execute {
            h.sendReport(dev, HidReports.REPORT_ID_MOUSE, HidReports.mouse(button, 0, 0))
            Thread.sleep(KEY_DELAY_MS)
            h.sendReport(
                dev, HidReports.REPORT_ID_MOUSE,
                HidReports.mouse(HidReports.BUTTON_NONE, 0, 0)
            )
        }
    }

    /** Sends a single key off the main thread. */
    fun typeKey(modifier: Byte, keyCode: Byte) {
        exec.execute { tapKey(modifier, keyCode) }
    }

    fun isReady(): Boolean = hid != null && connectedDevice != null

    fun deviceLabel(device: BluetoothDevice): String =
        runCatching { device.name ?: device.address }.getOrDefault("device")

    fun localBluetoothName(): String =
        runCatching { adapter?.name ?: "this phone" }.getOrDefault("this phone")

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(btStateReceiver) }
        runCatching { hid?.unregisterApp() }
        runCatching {
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        exec.shutdown()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BeamPad connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BeamPad")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification("starting")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        /** Gap between reports. Slower stacks drop keys sent back-to-back. */
        private const val KEY_DELAY_MS = 12L
        private const val KEY_LAYOUT = "layout"
        private const val CHANNEL_ID = "beampad_connection"
        private const val NOTIF_ID = 1
    }
}
