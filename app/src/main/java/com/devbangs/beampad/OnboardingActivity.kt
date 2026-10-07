package com.devbangs.beampad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.devbangs.beampad.databinding.ActivityOnboardingBinding
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Three steps, per the blueprint: what are you controlling, how to pair
 * it, which controls to start with; then straight into those controls.
 * No tour: advanced features are taught where they are met. The keyboard
 * layout is asked for computers, because a mismatch fails silently and
 * the user would have no way to guess why symbols arrive wrong.
 */
class OnboardingActivity : BeamActivity() {

    private lateinit var ui: ActivityOnboardingBinding
    private val prefs by lazy { Prefs(this) }

    private var step = 0
    private var type = DeviceType.TV
    private var mode = ControlMode.REMOTE

    // ---- Live pairing state for step 2 ----------------------------------------

    private var service: HidService? = null
    private var bound = false
    private var pairStatus: LinearLayout? = null
    private var celebrated = false

    private val stateListener: () -> Unit = { runOnUiThread { renderPairStatus() } }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? HidService.LocalBinder)?.service ?: return
            service = s
            s.addListener(stateListener)
            renderPairStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            renderPairStatus()
        }
    }

    /** Android 12 added the Nearby devices permissions; earlier versions need none at runtime. */
    private val bluetoothPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startAndBind()
        renderPairStatus()
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { renderPairStatus() }

    private val discoverable = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { renderPairStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        step = savedInstanceState?.getInt(KEY_STEP) ?: 0
        type = enumOrNull<DeviceType>(savedInstanceState?.getString(KEY_TYPE)) ?: prefs.targetType
        mode = enumOrNull<ControlMode>(savedInstanceState?.getString(KEY_MODE)) ?: suggested(type)

        buildProgress()
        ui.next.setOnClickListener {
            if (step == STEPS - 1) finishOnboarding() else {
                step++
                render()
            }
        }
        ui.back.setOnClickListener {
            if (step > 0) {
                step--
                render()
            }
        }
        ui.skip.setOnClickListener { finishOnboarding() }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
        outState.putString(KEY_TYPE, type.name)
        outState.putString(KEY_MODE, mode.name)
    }

    /** Presentation is Pro, so projectors start on the remote. */
    private fun suggested(t: DeviceType): ControlMode =
        t.suggestedMode.takeIf { Features.allowed(this, it) } ?: ControlMode.REMOTE

    private fun buildProgress() {
        ui.progress.removeAllViews()
        repeat(STEPS) { i ->
            ui.progress.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_progress_segment)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = Ui.dp(this@OnboardingActivity, 6)
            })
        }
    }

    private fun render() {
        for (i in 0 until ui.progress.childCount) ui.progress.getChildAt(i).isSelected = i <= step
        ui.stepLabel.text = getString(R.string.ob_step, step + 1, STEPS)
        ui.back.isVisible = step > 0
        ui.skip.isVisible = step < STEPS - 1
        ui.next.setText(if (step == STEPS - 1) R.string.ob_start else R.string.next)
        ui.options.removeAllViews()
        pairStatus = null

        when (step) {
            0 -> {
                ui.title.setText(R.string.ob_q1_title)
                ui.body.setText(R.string.ob_q1_body)
                listOf(DeviceType.TV, DeviceType.COMPUTER, DeviceType.PROJECTOR, DeviceType.OTHER).forEach { t ->
                    option(getString(t.labelRes), getString(typeDetail(t)), t.iconRes, t == type) {
                        type = t
                        mode = suggested(t)
                        render()
                    }
                }
            }
            1 -> {
                ui.title.setText(R.string.ob_q2_title)
                ui.body.setText(R.string.ob_q2_body)
                // Live state first: what to do now, and when it worked.
                pairStatus = Ui.card(ui.options).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = 0; bottomMargin = Ui.dp(context, 12) }
                }
                startAndBind()
                renderPairStatus()
                val name = runCatching {
                    getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.name
                }.getOrNull() ?: getString(R.string.this_phone)
                val lines = if (type == DeviceType.COMPUTER) listOf(
                    getString(R.string.pair_pc_1), getString(R.string.pair_pc_2, name), getString(R.string.pair_pc_3)
                ) else listOf(
                    getString(R.string.pair_step_1), getString(R.string.pair_step_2), getString(R.string.pair_step_3, name)
                )
                val card = Ui.card(ui.options).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = 0 }
                    setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8))
                }
                lines.forEachIndexed { i, line -> Ui.step(card, i + 1, line) }
                ui.options.addView(TextView(this).apply {
                    setTextAppearance(R.style.Text_Small_Faint)
                    text = getString(R.string.ob_q2_note)
                    setPadding(Ui.dp(context, 4), Ui.dp(context, 14), Ui.dp(context, 4), 0)
                })
            }
            else -> {
                ui.title.setText(R.string.ob_q3_title)
                ui.body.setText(R.string.ob_q3_body)
                listOf(ControlMode.REMOTE, ControlMode.KEYBOARD, ControlMode.TRACKPAD, ControlMode.MEDIA).forEach { m ->
                    option(getString(m.labelRes), getString(modeDetail(m)), m.iconRes, m == mode) {
                        mode = m
                        render()
                    }
                }
                if (type == DeviceType.COMPUTER) {
                    Ui.space(ui.options, 8)
                    val card = Ui.card(ui.options).apply {
                        (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = 0 }
                    }
                    var valueView: TextView? = null
                    valueView = Ui.valueRow(card, getString(R.string.settings_layout), prefs.layout.label,
                        getString(R.string.ob_layout_body), R.drawable.ic_keyboard) {
                        Sheets.choose(this, getString(R.string.settings_layout),
                            HidReports.Layout.entries.map { Sheets.Choice(it, it.label) }, prefs.layout) {
                            prefs.layout = it
                            valueView?.text = it.label
                        }
                    }.second
                }
            }
        }

        if (!Motion.reduced(this)) {
            listOf(ui.title, ui.body, ui.options).forEach { v ->
                v.alpha = 0f
                v.translationY = Ui.dp(this, 8).toFloat()
                v.animate().alpha(1f).translationY(0f).setDuration(220).start()
            }
        }
    }

    private fun hasPermissions(): Boolean = bluetoothPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        prefs.askedPermissions = true
        val wanted = bluetoothPermissions.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        runCatching { permissionRequest.launch(wanted.toTypedArray()) }
    }

    /** Starts and binds the connection service, as the main screen does, once permitted. */
    private fun startAndBind() {
        if (bound || !hasPermissions()) return
        val intent = Intent(this, HidService::class.java)
        runCatching { ContextCompat.startForegroundService(this, intent) }
        bound = runCatching { bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
    }

    private fun adapter(): BluetoothAdapter? =
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    /**
     * Step 2's live card: the one thing to do next (allow, turn on, make
     * visible), progress while connecting, and a clear success once the
     * device is connected. Already-paired devices connect with one tap.
     */
    private fun renderPairStatus() {
        val box = pairStatus ?: return
        if (step != 1 || isFinishing) return
        box.removeAllViews()
        val s = service
        val adapter = adapter()

        fun status(icon: Int, title: CharSequence, detail: CharSequence?, tone: Ui.Tone = Ui.Tone.ACCENT,
                   busy: Boolean = false, action: Int? = null, onAction: () -> Unit = {}) {
            val row = Ui.row(box, title, detail, icon, tone)
            row.subtitle.maxLines = 4
            if (busy) {
                row.trailing.addView(CircularProgressIndicator(this).apply {
                    isIndeterminate = true
                    indicatorSize = Ui.dp(context, 22)
                    trackThickness = Ui.dp(context, 3)
                })
                row.trailing.isVisible = true
            }
            if (action != null) {
                box.addView(
                    Ui.button(box, Ui.ButtonKind.SMALL_PRIMARY, getString(action), onClick = onAction),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = Ui.dp(this@OnboardingActivity, 12)
                        bottomMargin = Ui.dp(this@OnboardingActivity, 12)
                    }
                )
            }
        }

        when {
            !hasPermissions() -> status(R.drawable.ic_bluetooth, getString(R.string.status_permission),
                getString(R.string.status_permission_detail), action = R.string.action_allow) { requestPermissions() }
            adapter == null -> status(R.drawable.ic_bluetooth_slash, getString(R.string.status_no_bluetooth),
                getString(R.string.status_no_bluetooth_detail), Ui.Tone.DANGER)
            !runCatching { adapter.isEnabled }.getOrDefault(false) -> status(R.drawable.ic_bluetooth_slash,
                getString(R.string.status_bt_off), getString(R.string.status_bt_off_detail),
                action = R.string.action_turn_on) {
                runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
            s == null || s.state == HidService.State.STARTING -> status(R.drawable.ic_bluetooth,
                getString(R.string.status_starting), getString(R.string.status_starting_detail), busy = true)
            s.state == HidService.State.UNSUPPORTED -> status(R.drawable.ic_warning_circle,
                getString(R.string.status_unsupported), getString(
                    when (s.unsupportedReason) {
                        HidService.UnsupportedReason.NO_PROFILE -> R.string.unsupported_no_profile
                        HidService.UnsupportedReason.REFUSED -> R.string.unsupported_refused
                        else -> R.string.unsupported_no_response
                    }
                ), Ui.Tone.DANGER, action = R.string.ob_see_compat) { CompatibilityActivity.open(this) }
            s.isReady() -> {
                val name = s.connectedDevice?.let { s.deviceLabel(it) } ?: getString(R.string.device_fallback)
                status(R.drawable.ic_check_circle_fill, getString(R.string.ob_live_connected, name),
                    getString(R.string.ob_live_connected_body), Ui.Tone.LIVE)
                if (!celebrated) {
                    celebrated = true
                    Haptics.confirm(this)
                }
            }
            s.state == HidService.State.CONNECTING -> {
                val name = s.connectingDevice?.let { s.deviceLabel(it) } ?: getString(R.string.device_fallback)
                status(R.drawable.ic_bluetooth, getString(R.string.status_connecting_short),
                    getString(R.string.status_connecting_to, name), busy = true)
            }
            else -> {
                status(R.drawable.ic_bluetooth, getString(R.string.ob_live_waiting),
                    getString(R.string.ob_live_waiting_body, s.localBluetoothName()),
                    busy = true, action = R.string.ob_make_visible) {
                    runCatching {
                        discoverable.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
                        )
                    }
                }
                val paired = s.pairedHosts().take(MAX_PAIRED_SHOWN)
                if (paired.isNotEmpty()) {
                    Ui.space(box, 8)
                    Ui.section(box, getString(R.string.ob_already_paired))
                    paired.forEach { device ->
                        Ui.row(box, s.deviceLabel(device), getString(R.string.action_connect),
                            s.typeOf(device).iconRes) { s.connect(device) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        service?.removeListener(stateListener)
        if (bound) runCatching { unbindService(connection) }
    }

    /** A large selectable card: icon, name, one line of detail, a check when chosen. */
    private fun option(title: String, detail: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_choice_card, ui.options, false)
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.detail).text = detail
        view.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        view.findViewById<FrameLayout>(R.id.tile).isSelected = selected
        view.findViewById<View>(R.id.check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        view.isSelected = selected
        view.setOnClickListener {
            Haptics.tick(it)
            onClick()
        }
        ui.options.addView(view)
    }

    private fun typeDetail(t: DeviceType) = when (t) {
        DeviceType.TV -> R.string.ob_type_tv
        DeviceType.COMPUTER -> R.string.ob_type_computer
        DeviceType.PROJECTOR -> R.string.ob_type_projector
        else -> R.string.ob_type_other
    }

    private fun modeDetail(m: ControlMode) = when (m) {
        ControlMode.REMOTE -> R.string.ob_mode_remote
        ControlMode.KEYBOARD -> R.string.ob_mode_keyboard
        ControlMode.TRACKPAD -> R.string.ob_mode_trackpad
        else -> R.string.ob_mode_media
    }

    private fun finishOnboarding() {
        prefs.targetType = type
        prefs.lastMode = mode
        prefs.lastPanelId = null
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val KEY_SEEN = "onboarding_seen"
        private const val KEY_STEP = "step"
        private const val KEY_TYPE = "type"
        private const val KEY_MODE = "mode"
        private const val STEPS = 3
        private const val DISCOVERABLE_SECONDS = 180
        private const val MAX_PAIRED_SHOWN = 3

        fun shouldShow(activity: android.app.Activity): Boolean =
            !activity.getSharedPreferences(Prefs.FILE, MODE_PRIVATE).getBoolean(KEY_SEEN, false)
    }
}
