package com.devbangs.beampad

import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class HidSpikeActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var input: EditText

    private var service: HidService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as HidService.LocalBinder).service
            service = s
            bound = true
            s.listener = { msg -> runOnUiThread { log(msg) } }
            log("pair from the other device - look for \"${s.localBluetoothName()}\"")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val missing = granted.filterValues { !it }.keys
        if (missing.isEmpty()) startAndBind()
        else log("denied: ${missing.joinToString { it.substringAfterLast('.') }}")
    }

    private val discoverable = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode > 0) log("discoverable for ${result.resultCode}s")
        else log("discoverable refused")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { textSize = 13f }
        input = EditText(this).apply { hint = "text to send" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply {
                text = "Make discoverable"
                setOnClickListener {
                    discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE))
                }
            })
            addView(input)
            addView(Button(context).apply {
                text = "Send text"
                setOnClickListener { sendText(input.text.toString()) }
            })
            addView(ScrollView(context).apply {
                addView(status)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0
                ).apply { weight = 1f }
            })
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                bars.left + pad,
                bars.top + pad,
                bars.right + pad,
                maxOf(bars.bottom, ime.bottom) + pad
            )
            insets
        }

        setContentView(root)

        permissions.launch(
            buildList {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
                add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    add(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        )
    }

    private fun startAndBind() {
        val intent = Intent(this, HidService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun sendText(text: String) {
        if (text.isEmpty()) {
            Toast.makeText(this, "nothing to send", Toast.LENGTH_SHORT).show()
            return
        }
        val s = service
        if (s == null || !s.isReady()) {
            Toast.makeText(this, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        s.typeText(text) { sent, skipped ->
            runOnUiThread {
                log("sent $sent chars" + if (skipped > 0) ", $skipped unsupported" else "")
            }
        }
    }

    private fun log(msg: String) {
        status.append("$msg\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        service?.listener = null
        if (bound) unbindService(connection)
        // service keeps running; notification action will stop it later
    }
}
