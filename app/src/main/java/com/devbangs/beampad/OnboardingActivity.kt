package com.devbangs.beampad

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.devbangs.beampad.databinding.ActivityOnboardingBinding

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

        fun shouldShow(activity: android.app.Activity): Boolean =
            !activity.getSharedPreferences(Prefs.FILE, MODE_PRIVATE).getBoolean(KEY_SEEN, false)
    }
}
