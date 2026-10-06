package com.devbangs.beampad

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.devbangs.beampad.databinding.FragmentKeyboardBinding
import com.devbangs.beampad.databinding.SheetKeysBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.roundToInt

class KeyboardFragment : Fragment() {

    private var _ui: FragmentKeyboardBinding? = null
    private val ui get() = _ui!!

    private val host get() = activity as? MainActivity
    private val app get() = requireActivity().application as BeamPadApp

    private var keysSheet: BottomSheetDialog? = null

    private val connectionObserver: (Boolean) -> Unit = { connected ->
        _ui?.let { view ->
            // Controls stay live-looking in both states: a greyed screen on
            // first launch reads as broken rather than as not-yet-paired.
            // The press is where the difference shows, as a nudge to connect.
            view.dpad.connected = connected
            if (!connected) liveBaseline = view.input.text?.toString().orEmpty()
        }
    }

    private val entitlementObserver: (Entitlements.Change) -> Unit = { renderTools() }

    private val speech = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@registerForActivityResult
        insert(heard)
    }

    /**
     * Live typing mirrors the field onto the TV: what was there last time
     * versus what is there now, as backspaces then new characters. Diffing
     * the whole field rather than listening for keys also covers autocorrect
     * and voice input, which replace text instead of typing it.
     */
    private var liveBaseline = ""
    private var suppressWatcher = false

    private val liveWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (suppressWatcher || !liveTyping()) return
            val now = s?.toString().orEmpty()
            val service = requireConnection()
            if (service == null) {
                // Nothing reached the TV, so nothing is owed to it later.
                liveBaseline = now
                return
            }
            val common = liveBaseline.commonPrefixWith(now).length
            repeat(liveBaseline.length - common) {
                service.typeKey(HidReports.MOD_NONE, HidReports.KEY_BACKSPACE)
            }
            val added = now.substring(common)
            if (added.isNotEmpty()) service.typeText(added) { _, _ -> }
            liveBaseline = now
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View {
        _ui = FragmentKeyboardBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        ui.send.setOnClickListener { submit() }

        // Pressing send on the phone's own keyboard also presses Enter on the
        // TV, so a search box or password field submits. The Send button
        // types the text without Enter, for fields that should not submit.
        ui.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (liveTyping()) pressEnterLive() else sendInput(withEnter = true)
                true
            } else false
        }
        ui.input.addTextChangedListener(liveWatcher)

        ui.dpad.onKey = { k ->
            buzz(ui.dpad)
            when (k) {
                DpadView.Key.UP -> key(HidReports.KEY_UP)
                DpadView.Key.DOWN -> key(HidReports.KEY_DOWN)
                DpadView.Key.LEFT -> key(HidReports.KEY_LEFT)
                DpadView.Key.RIGHT -> key(HidReports.KEY_RIGHT)
                DpadView.Key.OK -> key(HidReports.KEY_ENTER)
            }
        }

        // Escape rather than the consumer Back usage: Android TV honours it
        // more consistently.
        tap(ui.back) { key(HidReports.KEY_ESC) }
        tap(ui.backspace) { key(HidReports.KEY_BACKSPACE) }

        tap(ui.home) { consumer(HidReports.CC_HOME) }
        tap(ui.menu) { consumer(HidReports.CC_MENU) }
        tap(ui.rewind) { consumer(HidReports.CC_SCAN_PREV) }
        tap(ui.playPause) { consumer(HidReports.CC_PLAY_PAUSE) }
        tap(ui.forward) { consumer(HidReports.CC_SCAN_NEXT) }

        tap(ui.volUp) { consumer(HidReports.CC_VOLUME_UP) }
        tap(ui.volDown) { consumer(HidReports.CC_VOLUME_DOWN) }
        tap(ui.mute) { consumer(HidReports.CC_MUTE) }

        ui.mic.setOnClickListener { startVoice() }
        ui.chipLive.setOnClickListener { toggleLive() }
        ui.chipPaste.setOnClickListener { pasteClipboard() }
        ui.chipKeys.setOnClickListener { showKeys() }

        ui.scroll.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) v.post { fitDpad() }
        }

        app.observeEntitlement(entitlementObserver)
        renderTools()
        host?.observeConnection(connectionObserver)
    }

    override fun onResume() {
        super.onResume()
        // Pro may have started or lapsed while another screen was open.
        renderTools()
    }

    private fun liveTyping(): Boolean = context?.let { Features.liveTyping(it) } == true

    private fun renderTools() {
        val ui = _ui ?: return
        val pro = Features.isPro(requireContext())
        ui.lockLive.isVisible = !pro
        ui.lockPaste.isVisible = !pro
        ui.lockKeys.isVisible = !pro
        ui.micLock.isVisible = !pro

        val live = liveTyping()
        ui.chipLive.isSelected = live
        ui.input.setHint(if (live) R.string.keyboard_hint_live else R.string.keyboard_hint)
        ui.send.setIconResource(
            if (live) R.drawable.ic_arrow_elbow_down_left else R.drawable.ic_paper_plane_right
        )
        ui.send.contentDescription = getString(if (live) R.string.key_enter else R.string.keyboard_send)
    }

    /** Compact keys and gaps, for screens where the normal remote cannot fit. */
    private var compact = false

    /** Height of everything but the D-pad in the normal layout. */
    private var normalFixed = 0

    /**
     * Sizes the D-pad row to the room that is left: tall phones get a big
     * pad, short or zoomed ones a smaller pad instead of a cut-off remote.
     * When even the smallest pad will not fit, keys and gaps tighten too.
     * Re-run whenever the tab changes height, as when a banner loads.
     *
     * The compact decision uses the normal layout's measurement, so
     * tightening cannot make room that flips it straight back.
     */
    private fun fitDpad() {
        val ui = _ui ?: return
        val viewport = ui.scroll.height
        if (viewport == 0) return

        val fixed = fixedHeight(ui)
        if (!compact) normalFixed = fixed

        val needCompact = viewport - normalFixed - dp(MIN_SLACK_DP) < dp(DPAD_MIN_DP)
        if (needCompact != compact) {
            compact = needCompact
            applyCompact(ui, needCompact)
            ui.scroll.post { fitDpad() }
            return
        }

        val slack = dp(if (compact) COMPACT_SLACK_DP else MIN_SLACK_DP)
        val min = dp(if (compact) DPAD_MIN_COMPACT_DP else DPAD_MIN_DP)
        val target = (viewport - fixed - slack).coerceIn(min, dp(DPAD_MAX_DP))
        val rowParams = ui.dpadRow.layoutParams
        if (rowParams.height != target) {
            rowParams.height = target
            ui.dpadRow.layoutParams = rowParams
        }
    }

    private fun fixedHeight(ui: FragmentKeyboardBinding): Int {
        var fixed = ui.column.paddingTop + ui.column.paddingBottom
        for (child in listOf(ui.inputRow, ui.toolsRow, ui.navRow, ui.mediaRow)) {
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            fixed += child.height + lp.topMargin + lp.bottomMargin
        }
        val rowParams = ui.dpadRow.layoutParams as ViewGroup.MarginLayoutParams
        return fixed + rowParams.topMargin + rowParams.bottomMargin
    }

    private fun applyCompact(ui: FragmentKeyboardBinding, on: Boolean) {
        fun size(view: View, heightDp: Int, widthDp: Int? = null) {
            val lp = view.layoutParams
            lp.height = dp(heightDp)
            if (widthDp != null) lp.width = dp(widthDp)
            view.layoutParams = lp
        }
        fun top(view: View, marginDp: Int) {
            val lp = view.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = dp(marginDp)
            view.layoutParams = lp
        }

        size(ui.inputField, if (on) 48 else 56)
        size(ui.send, if (on) 48 else 56, if (on) 48 else 56)
        listOf(ui.chipLive, ui.chipPaste, ui.chipKeys).forEach { size(it, if (on) 34 else 38) }
        listOf(ui.home, ui.back, ui.menu).forEach { size(it, if (on) 42 else 48) }
        listOf(ui.rewind, ui.playPause, ui.forward).forEach { size(it, if (on) 42 else 52) }
        top(ui.toolsRow, if (on) 6 else 10)
        listOf(ui.navRow, ui.dpadRow, ui.mediaRow).forEach { top(it, if (on) 8 else 12) }
        ui.column.setPadding(
            ui.column.paddingLeft, dp(if (on) 2 else 4),
            ui.column.paddingRight, dp(if (on) 8 else 12)
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    /** Short tick on every key, when the user has haptics on. */
    private fun buzz(view: View) {
        if (app.prefs.haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun tap(view: View, action: () -> Unit) {
        view.setOnClickListener {
            buzz(it)
            action()
        }
    }

    private var lastNudge = 0L

    /**
     * Controls look live whether or not anything is paired, so a press with
     * no connection has to say so. Rate-limited: one nudge per few seconds,
     * not one per key.
     */
    private fun requireConnection(): HidService? {
        val service = host?.service
        if (service != null && service.isReady()) return service

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastNudge > NUDGE_INTERVAL_MS) {
            lastNudge = now
            context?.let { Toast.makeText(it, R.string.not_connected_hint, Toast.LENGTH_SHORT).show() }
        }
        return null
    }

    private fun consumer(usage: Int) {
        requireConnection()?.consumerKey(usage)
    }

    private fun key(code: Byte, modifier: Byte = HidReports.MOD_NONE) {
        requireConnection()?.typeKey(modifier, code)
    }

    private fun submit() {
        if (liveTyping()) pressEnterLive() else sendInput(withEnter = false)
    }

    /** In live mode the text is already on the TV; only Enter is left. */
    private fun pressEnterLive() {
        val service = requireConnection() ?: return
        service.typeKey(HidReports.MOD_NONE, HidReports.KEY_ENTER)
        clearField()
    }

    private fun clearField() {
        suppressWatcher = true
        ui.input.setText("")
        suppressWatcher = false
        liveBaseline = ""
    }

    private fun sendInput(withEnter: Boolean) {
        val text = ui.input.text?.toString().orEmpty()
        if (text.isEmpty()) return

        val service = requireConnection() ?: return

        service.typeText(if (withEnter) text + "\n" else text) { _, skipped ->
            activity?.runOnUiThread {
                if (_ui == null) return@runOnUiThread
                clearField()
                if (skipped > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.chars_skipped, skipped),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun toggleLive() {
        val context = requireContext()
        if (!Features.isPro(context)) {
            ProActivity.open(context, Features.Pro.LIVE_TYPING)
            return
        }
        val turningOn = !app.prefs.liveTyping
        app.prefs.liveTyping = turningOn
        // Whatever is already in the field is not retyped when live starts.
        liveBaseline = ui.input.text?.toString().orEmpty()
        renderTools()
        Toast.makeText(
            context,
            if (turningOn) R.string.live_on else R.string.live_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startVoice() {
        val context = requireContext()
        if (!Features.isPro(context)) {
            ProActivity.open(context, Features.Pro.VOICE)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        // Phones without Google's speech service have nothing to answer this.
        val launched = runCatching { speech.launch(intent) }.isSuccess
        if (!launched) {
            Toast.makeText(context, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** Adds dictated text to the field. In live mode the watcher sends it. */
    private fun insert(text: String) {
        val ui = _ui ?: return
        val current = ui.input.text?.toString().orEmpty()
        val joined = if (current.isEmpty() || current.endsWith(" ")) current + text
        else "$current $text"
        ui.input.setText(joined)
        ui.input.setSelection(joined.length)
        ui.input.requestFocus()
    }

    private fun pasteClipboard() {
        val context = requireContext()
        if (!Features.isPro(context)) {
            ProActivity.open(context, Features.Pro.CLIPBOARD)
            return
        }
        val service = requireConnection() ?: return
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        if (text.isBlank()) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        service.typeText(text) { sent, _ ->
            activity?.runOnUiThread {
                if (_ui == null) return@runOnUiThread
                Toast.makeText(
                    requireContext(),
                    resources.getQuantityString(R.plurals.clipboard_sent, sent, sent),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showKeys() {
        val context = requireContext()
        if (!Features.isPro(context)) {
            ProActivity.open(context, Features.Pro.PRO_KEYS)
            return
        }
        val sheetUi = SheetKeysBinding.inflate(layoutInflater)
        val keys = listOf(
            sheetUi.kTab to HidReports.KEY_TAB,
            sheetUi.kEsc to HidReports.KEY_ESC,
            sheetUi.kEnter to HidReports.KEY_ENTER,
            sheetUi.kHome to HidReports.KEY_HOME,
            sheetUi.kEnd to HidReports.KEY_END,
            sheetUi.kDelete to HidReports.KEY_DELETE,
            sheetUi.kPageUp to HidReports.KEY_PAGE_UP,
            sheetUi.kPageDown to HidReports.KEY_PAGE_DOWN
        )
        keys.forEach { (button, code) -> tap(button) { key(code) } }
        tap(sheetUi.kSelectAll) { key(HidReports.KEY_A, HidReports.MOD_LEFT_CTRL) }

        keysSheet?.dismiss()
        keysSheet = BottomSheetDialog(context).apply {
            setContentView(sheetUi.root)
            show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        keysSheet?.dismiss()
        keysSheet = null
        app.stopObservingEntitlement(entitlementObserver)
        host?.stopObserving(connectionObserver)
        _ui = null
    }

    private companion object {
        const val NUDGE_INTERVAL_MS = 3000L
        const val DPAD_MIN_DP = 128
        const val DPAD_MIN_COMPACT_DP = 108
        const val DPAD_MAX_DP = 230
        const val MIN_SLACK_DP = 24
        const val COMPACT_SLACK_DP = 8
    }
}
