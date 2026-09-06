package com.devbangs.beampad

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.concurrent.Executors

class HidSpikeActivity : ComponentActivity() {

    private lateinit var status: TextView
    private var hid: BluetoothHidDevice? = null
    private var target: BluetoothDevice? = null
    private val exec = Executors.newSingleThreadExecutor()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            log("permissions granted")
            initHid()
        } else {
            log("PERMISSIONS DENIED - cannot continue")
        }
    }

    private val discoverable = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { log("discoverable result code=${it.resultCode}") }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(plugged: BluetoothDevice?, registered: Boolean) {
            log(if (registered) "GATE 2 PASS registered=true" else "GATE 2 FAIL registered=false")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val name = when (state) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "connecting"
                BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                else -> "state $state"
            }
            log("GATE 3 $name")
            if (state == BluetoothProfile.STATE_CONNECTED) target = device
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            log("GATE 1 PASS proxy acquired")
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "BeamPad",
                "Phone as TV keyboard",
                "Dev_Bangs",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                KEYBOARD_DESCRIPTOR
            )
            runCatching { hid?.registerApp(sdp, null, null, exec, callback) }
                .onFailure { log("registerApp threw: ${it.message}") }
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            log("HID service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { textSize = 13f }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply {
                text = "1. Make discoverable"
                setOnClickListener {
                    discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE))
                }
            })
            addView(Button(context).apply {
                text = "2. Type 'a'"
                setOnClickListener { sendKey(KEY_A) }
            })
            addView(ScrollView(context).apply {
                addView(status)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0
                ).apply { weight = 1f }
            })
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setContentView(root)

        permissions.launch(
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            )
        )
    }

    private fun initHid() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { log("GATE 1 FAIL no bluetooth adapter"); return }
        if (!adapter.isEnabled) { log("bluetooth is OFF - turn it on and reopen"); return }
        val ok = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE)
        log("getProfileProxy returned $ok")
        if (!ok) log("GATE 1 FAIL this phone has no HID Device profile")
    }

    private fun sendKey(code: Byte) {
        val dev = target
        if (dev == null) { log("no connected device yet"); return }
        hid?.sendReport(dev, 1, byteArrayOf(0, 0, code, 0, 0, 0, 0, 0))
        hid?.sendReport(dev, 1, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        log("sent key")
    }

    private fun log(msg: String) = runOnUiThread {
        status.append("$msg\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { hid?.unregisterApp() }
        exec.shutdown()
    }

    companion object {
        private const val KEY_A: Byte = 0x04
        private val KEYBOARD_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x85.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
            0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x03,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65,
            0x81.toByte(), 0x00,
            0xC0.toByte()
        )
    }
}
