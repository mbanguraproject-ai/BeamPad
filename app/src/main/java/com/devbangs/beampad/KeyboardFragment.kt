package com.devbangs.beampad

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.devbangs.beampad.databinding.FragmentKeyboardBinding
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/**
 * The keyboard surface. The phone's own keyboard types (so autocorrect,
 * swipe, voice and every language work); this screen adds navigation
 * keys, sticky modifiers, optional F1-F12, TV search, and the Pro tools:
 * live typing, voice, clipboard and the full PC keyboard, which takes
 * over the whole surface when on.
 */
class KeyboardFragment : SurfaceFragment() {

    override val firstTip = R.string.tip_keyboard

    private var _ui: FragmentKeyboardBinding? = null
    private val ui get() = _ui!!

    /** Modifier bits armed for the next key, cleared after it is sent. */
    private var modifiers = 0

    /** While true, the next send presses Enter, then search mode ends. */
    private var searchMode = false

    /** Sent this session, newest first. Memory only; never stored. */
    private val recent = ArrayDeque<String>()

    private val entitlementObserver: (Entitlements.Change) -> Unit = {
        renderTools()
        renderMode()
    }

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
     * Live typing mirrors the field onto the device: what was there last
     * time versus what is there now, as backspaces then new characters.
     * Diffing the whole field rather than listening for keys also covers
     * autocorrect and voice input, which replace text instead of typing it.
     */
    private var liveBaseline = ""
    private var suppressWatcher = false

    private val liveWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (suppressWatcher || !liveTyping()) return
            val now = s?.toString().orEmpty()
            val service = service()
            if (service == null) {
                // Nothing reached the device, so nothing is owed to it later.
                liveBaseline = now
                return
            }
            val common = liveBaseline.commonPrefixWith(now).length
            val erase = liveBaseline.length - common
            val added = now.substring(common)
            // One ordered step, so an autocorrect's backspaces cannot land in
            // the middle of the characters it is correcting.
            if (erase > 0 || added.isNotEmpty()) service.engine.keyboard.replace(erase, added)
            liveBaseline = now
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentKeyboardBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        ui.send.setOnClickListener {
            Haptics.tick(it)
            // While text is going out the button is Stop.
            val inFlight = sending
            if (inFlight != null) inFlight.cancel() else submit()
        }

        // The phone keyboard's action key also presses Enter on the device
        // (per the Enter setting), so a search box or password field submits.
        // The Send button types without Enter, for fields that should not.
        ui.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (sending != null) return@setOnEditorActionListener true
                val enter = searchMode || app.prefs.enterBehavior == Prefs.EnterBehavior.SEND_AND_ENTER
                if (liveTyping()) pressEnterLive() else sendInput(withEnter = enter)
                true
            } else false
        }
        ui.input.addTextChangedListener(liveWatcher)

        ui.mic.setOnClickListener { startVoice() }
        ui.chipLive.setOnClickListener { toggleLive() }
        ui.chipSearch.setOnClickListener { startTvSearch() }
        ui.chipPaste.setOnClickListener { pasteClipboard() }
        ui.chipKeys.setOnClickListener { setFull(true) }
        ui.fullType.setOnClickListener {
            Haptics.tick(it)
            setFull(false)
        }
        ui.fullBack.setOnClickListener { setFull(false) }
        ui.fullUnlock.setOnClickListener {
            ProActivity.open(requireContext(), Features.Pro.FULL_KEYBOARD)
        }
        ui.fullKeyboard.onKey = { key, mods -> sendFullKey(key, mods) }
        buildFullStrips()

        bindNav(ui.esc, HidReports.KEY_ESC)
        bindNav(ui.tab, HidReports.KEY_TAB)
        bindNav(ui.enter, HidReports.KEY_ENTER)
        bindNav(ui.space, HidReports.KEY_SPACE)
        bindNavRepeating(ui.up, HidReports.KEY_UP)
        bindNavRepeating(ui.down, HidReports.KEY_DOWN)
        bindNavRepeating(ui.left, HidReports.KEY_LEFT)
        bindNavRepeating(ui.right, HidReports.KEY_RIGHT)
        bindNavRepeating(ui.backspace, HidReports.KEY_BACKSPACE)
        bind(ui.home, Action.Consumer(HidReports.CC_HOME))

        listOf(
            ui.modCtrl to HidReports.MOD_LEFT_CTRL,
            ui.modAlt to HidReports.MOD_LEFT_ALT,
            ui.modShift to HidReports.MOD_LEFT_SHIFT,
            ui.modMeta to HidReports.MOD_LEFT_META
        ).forEach { (button, bit) ->
            button.setOnClickListener {
                if (!requireProKeys()) return@setOnClickListener
                Haptics.tick(it)
                modifiers = modifiers xor bit.toInt()
                renderModifiers()
            }
        }

        buildFunctionKeys()
        app.observeEntitlement(entitlementObserver)
        renderTools()
        renderRecent()
    }

    override fun onResume() {
        super.onResume()
        // Pro may have started or lapsed, and settings may have changed.
        renderTools()
        renderMode()
        val scale = ControlSizing.scale(requireContext())
        listOf(ui.esc, ui.up, ui.backspace, ui.tab, ui.left, ui.down, ui.right, ui.enter, ui.space, ui.home)
            .forEach { it.updateLayoutParams { height = (dp(56) * scale).roundToInt() } }
        ui.fullKeyboard.maxKeyHeight = dp(56) * scale
    }

    override fun onPause() {
        super.onPause()
        // Nothing stays armed while the user is away.
        _ui?.fullKeyboard?.clearModifiers()
    }

    // ---- Full PC keyboard -------------------------------------------------------

    private fun setFull(full: Boolean) {
        if (app.prefs.fullKeyboard == full) return
        app.prefs.fullKeyboard = full
        if (full) {
            // The phone keyboard would cover the full one.
            ui.input.clearFocus()
            requireContext().getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(ui.input.windowToken, 0)
        } else {
            ui.fullKeyboard.clearModifiers()
        }
        renderMode()
    }

    /** Shows either the phone-keyboard surface or the full keyboard. */
    private fun renderMode() {
        val ui = _ui ?: return
        val prefs = app.prefs
        val full = prefs.fullKeyboard
        listOf(ui.inputRow, ui.toolsRow, ui.recentArea, ui.navRow1, ui.navRow2, ui.navRow3)
            .forEach { it.isVisible = !full }
        ui.modsRow.isVisible = !full && prefs.showModifiers
        ui.fkeysScroll.isVisible = !full && prefs.showFunctionKeys
        ui.fullPanel.isVisible = full
        if (!full) return

        // Free users see the keyboard, dimmed and inert, behind the upgrade card.
        val pro = Features.isPro(requireContext())
        ui.fullLock.isVisible = !pro
        ui.fullBody.alpha = if (pro) 1f else LOCKED_ALPHA
        ui.fullBody.importantForAccessibility =
            if (pro) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ui.fullKeyboard.locked = !pro
        ui.fullUnlock.setText(
            if (app.billing.plans.any { it.freeTrial != null }) R.string.fullkb_lock_trial
            else R.string.fullkb_lock_unlock
        )
    }

    /**
     * Characters go through the keyboard layout setting, so the key labelled
     * "z" types z (and Ctrl+Z undoes) whatever layout the device uses; every
     * other key is sent by position.
     */
    private fun sendFullKey(key: FullKeyboardView.Key, mods: Int) {
        val service = service() ?: return
        val shift = HidReports.MOD_LEFT_SHIFT.toInt()
        if (key.kind == FullKeyboardView.Kind.CHAR) {
            val char = (if (mods and shift != 0) key.shifted else key.label)?.firstOrNull()
            val encoded = char?.let { HidReports.encode(it, app.prefs.layout) }
            if (encoded != null) {
                val (charMods, code) = encoded
                service.typeKey((charMods.toInt() or (mods and shift.inv())).toByte(), code)
                return
            }
        }
        service.typeKey(mods.toByte(), key.code.toByte())
    }

    /** Shortcuts along the top and the extra keys row above the keyboard. */
    private fun buildFullStrips() {
        val shortcuts = listOf(
            "copy", "paste", "cut", "undo", "redo", "select_all", "find", "save",
            "app_switch", "task_view", "show_desktop", "file_explorer", "lock_pc",
            "task_manager", "screenshot", "new_tab", "close_tab", "refresh",
            "browser_back", "browser_forward", "close_window", "zoom_in", "zoom_out"
        )
        val extraKeys = (1..12).map { "f$it" } +
            listOf("print_screen", "insert", "caps_lock", "context_menu", "start_menu")
        fill(ui.shortcutStrip, shortcuts)
        fill(ui.fullFkeys, extraKeys)
    }

    private fun fill(strip: LinearLayout, ids: List<String>) {
        strip.removeAllViews()
        ids.mapNotNull { Actions.byId(it) }.forEachIndexed { i, named ->
            val key = layoutInflater.inflate(R.layout.ui_key_small, strip, false) as MaterialButton
            key.text = Actions.label(requireContext(), named)
            key.minWidth = dp(52)
            key.setPadding(dp(14), 0, dp(14), 0)
            key.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { if (i > 0) marginStart = dp(8) }
            key.setOnClickListener {
                if (!requireProKeys(Features.Pro.FULL_KEYBOARD)) return@setOnClickListener
                Haptics.tick(it)
                perform(named.action)
            }
            strip.addView(key)
        }
    }

    override fun onConnectionChanged(connected: Boolean, changed: Boolean) {
        if (!connected) liveBaseline = ui.input.text?.toString().orEmpty()
    }

    // ---- Keys -----------------------------------------------------------------

    private fun bindNav(view: View, code: Byte) {
        view.setOnClickListener {
            Haptics.tick(it)
            sendKey(code)
        }
    }

    /** Arrows and Backspace repeat while held; armed modifiers apply to the first press only. */
    private fun bindNavRepeating(view: View, code: Byte) {
        val repeat = object : Runnable {
            override fun run() {
                if (!view.isPressed) return
                service(nudge = false)?.typeKey(HidReports.MOD_NONE, code)
                view.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        @Suppress("ClickableViewAccessibility")
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    Haptics.tick(v)
                    sendKey(code)
                    v.postDelayed(repeat, REPEAT_DELAY_MS)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(repeat)
                }
            }
            true
        }
        view.setOnClickListener { sendKey(code) }
    }

    private fun sendKey(code: Byte) {
        val service = service() ?: return
        service.typeKey(modifiers.toByte(), code)
        clearModifiers()
    }

    private fun clearModifiers() {
        if (modifiers == 0) return
        modifiers = 0
        renderModifiers()
    }

    private fun renderModifiers() {
        ui.modCtrl.isSelected = modifiers and HidReports.MOD_LEFT_CTRL.toInt() != 0
        ui.modAlt.isSelected = modifiers and HidReports.MOD_LEFT_ALT.toInt() != 0
        ui.modShift.isSelected = modifiers and HidReports.MOD_LEFT_SHIFT.toInt() != 0
        ui.modMeta.isSelected = modifiers and HidReports.MOD_LEFT_META.toInt() != 0
    }

    private fun buildFunctionKeys() {
        val inflater = layoutInflater
        for (n in 1..12) {
            val key = inflater.inflate(R.layout.ui_key_small, ui.fkeys, false) as MaterialButton
            key.text = getString(R.string.act_f_template, n)
            key.updateLayoutParams<LinearLayout.LayoutParams> {
                width = dp(56)
                weight = 0f
                if (n > 1) marginStart = dp(8)
            }
            val code = HidReports.functionKey(n)
            key.setOnClickListener {
                if (!requireProKeys()) return@setOnClickListener
                Haptics.tick(it)
                sendKey(code)
            }
            ui.fkeys.addView(key)
        }
    }

    /** Modifiers and function keys are part of Pro keys; navigation keys are free. */
    private fun requireProKeys(feature: Features.Pro = Features.Pro.PRO_KEYS): Boolean {
        val context = requireContext()
        if (Features.isPro(context)) return true
        ProActivity.open(context, feature)
        return false
    }

    // ---- Tools ------------------------------------------------------------------

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
        ui.chipSearch.isSelected = searchMode
        ui.input.setHint(
            when {
                searchMode -> R.string.keyboard_hint_search
                live -> R.string.keyboard_hint_live
                else -> R.string.keyboard_hint
            }
        )
        renderSendButton()
    }

    private fun renderSendButton() {
        val ui = _ui ?: return
        val live = liveTyping()
        ui.send.setIconResource(
            when {
                sending != null -> R.drawable.ic_stop
                live -> R.drawable.ic_arrow_elbow_down_left
                else -> R.drawable.ic_paper_plane_right
            }
        )
        ui.send.contentDescription = getString(
            when {
                sending != null -> R.string.send_stop
                live -> R.string.key_enter
                else -> R.string.keyboard_send
            }
        )
    }

    /** The text send in flight. Another Send meanwhile would type the text twice. */
    private var sending: InputEngine.Job? = null

    /**
     * Types [payload] unless a send is already going out; [done] gets the
     * outcome on the main thread. False when refused as busy.
     */
    private fun typeOut(
        service: HidService,
        payload: String,
        done: (InputEngine.TypeResult) -> Unit
    ): Boolean {
        if (sending != null) {
            toast(getString(R.string.typing_busy))
            return false
        }
        sending = service.engine.keyboard.type(payload) { result ->
            sending = null
            renderSendButton()
            done(result)
        }
        renderSendButton()
        return true
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    private fun submit() {
        if (liveTyping()) pressEnterLive() else sendInput(withEnter = searchMode)
    }

    /** In live mode the text is already on the device; only Enter is left. */
    private fun pressEnterLive() {
        val service = service() ?: return
        service.typeKey(HidReports.MOD_NONE, HidReports.KEY_ENTER)
        remember(ui.input.text?.toString().orEmpty())
        clearField()
        endSearch()
    }

    private fun clearField() {
        suppressWatcher = true
        ui.input.setText("")
        suppressWatcher = false
        liveBaseline = ""
    }

    private fun sendInput(withEnter: Boolean) {
        val text = ui.input.text?.toString().orEmpty()
        if (text.isEmpty()) {
            // An empty send with Enter wanted is just Enter.
            if (withEnter) sendKey(HidReports.KEY_ENTER)
            return
        }
        val service = service() ?: return

        // With a modifier armed, a single character is a shortcut: Ctrl+C.
        if (modifiers != 0 && text.length == 1) {
            val encoded = HidReports.encode(text[0], app.prefs.layout)
            if (encoded != null) {
                val (mod, code) = encoded
                service.typeKey((mod.toInt() or modifiers).toByte(), code)
                clearModifiers()
                clearField()
                return
            }
        }

        typeOut(service, if (withEnter) text + "\n" else text) { result ->
            if (_ui == null) return@typeOut
            val field = ui.input.text?.toString().orEmpty()
            // Only touch the field if it still holds what was sent: the user
            // may have started the next message meanwhile.
            val untouched = field == text
            if (result.complete) {
                remember(text)
                if (untouched) clearField()
                endSearch()
                if (result.skipped > 0) toast(getString(R.string.chars_skipped, result.skipped))
                return@typeOut
            }
            // Dropped or stopped part way: what never reached the device
            // stays in the box, ready to send again.
            if (result.consumed > 0) remember(text.take(result.consumed))
            if (untouched) {
                val rest = text.drop(result.consumed)
                if (rest.isEmpty()) clearField() else setField(rest)
            }
            if (result.interrupted) toast(getString(R.string.send_interrupted))
        }
    }

    private fun setField(text: String) {
        suppressWatcher = true
        ui.input.setText(text)
        ui.input.setSelection(text.length)
        suppressWatcher = false
        liveBaseline = text
    }

    /** Opens the device's search, then the next send types into it and presses Enter. */
    private fun startTvSearch() {
        Haptics.tick(ui.chipSearch)
        service()?.consumerKey(HidReports.CC_SEARCH) ?: return
        searchMode = true
        renderTools()
        ui.input.requestFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(ui.input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun endSearch() {
        if (!searchMode) return
        searchMode = false
        renderTools()
    }

    private fun remember(text: String) {
        if (text.isBlank()) return
        recent.remove(text)
        recent.addFirst(text)
        while (recent.size > MAX_RECENT) recent.removeLast()
        renderRecent()
    }

    private fun renderRecent() {
        val ui = _ui ?: return
        ui.recentList.removeAllViews()
        ui.emptyHint.isVisible = recent.isEmpty()
        ui.recentScroll.isVisible = recent.isNotEmpty()
        recent.forEach { text ->
            val row = layoutInflater.inflate(R.layout.item_recent, ui.recentList, false)
            row.findViewById<TextView>(R.id.text).text = text
            row.setOnClickListener {
                Haptics.tick(it)
                val service = service() ?: return@setOnClickListener
                typeOut(service, text) { result ->
                    if (result.interrupted) {
                        toast(getString(R.string.paste_interrupted, result.consumed, text.length))
                    }
                }
            }
            ui.recentList.addView(row)
        }
    }

    private fun toggleLive() {
        val context = requireContext()
        if (!Features.isPro(context)) {
            ProActivity.open(context, Features.Pro.LIVE_TYPING)
            return
        }
        Haptics.tick(ui.chipLive)
        val turningOn = !app.prefs.liveTyping
        app.prefs.liveTyping = turningOn
        // Whatever is already in the field is not retyped when live starts.
        liveBaseline = ui.input.text?.toString().orEmpty()
        renderTools()
        Toast.makeText(context, if (turningOn) R.string.live_on else R.string.live_off, Toast.LENGTH_SHORT).show()
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
        if (!launched) Toast.makeText(context, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
    }

    /** Adds dictated text to the field. In live mode the watcher sends it. */
    private fun insert(text: String) {
        val ui = _ui ?: return
        val current = ui.input.text?.toString().orEmpty()
        val joined = if (current.isEmpty() || current.endsWith(" ")) current + text else "$current $text"
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
        val service = service() ?: return
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val send = {
            typeOut(service, text) { result ->
                val context = context ?: return@typeOut
                toast(
                    if (result.interrupted) {
                        getString(R.string.paste_interrupted, result.consumed, text.length)
                    } else {
                        context.resources.getQuantityString(R.plurals.clipboard_sent, result.sent, result.sent)
                    }
                )
            }
        }
        // The clipboard can hold anything; with confirmation on, the user
        // sees what is about to be typed before it leaves the phone.
        if (app.prefs.confirmClipboard) {
            Sheets.confirm(
                context,
                getString(R.string.clipboard_confirm_title),
                text.take(CLIPBOARD_PREVIEW),
                getString(R.string.clipboard_confirm_send)
            ) { send() }
        } else {
            send()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // A send in flight keeps going; its result just has no field to update.
        app.stopObservingEntitlement(entitlementObserver)
        _ui = null
    }

    private companion object {
        const val MAX_RECENT = 12
        const val LOCKED_ALPHA = 0.35f
        const val CLIPBOARD_PREVIEW = 400
    }
}
