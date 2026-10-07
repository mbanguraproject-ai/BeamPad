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
import android.widget.GridLayout
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
 * live typing, voice, clipboard and the extra keys sheet.
 */
class KeyboardFragment : SurfaceFragment() {

    private var _ui: FragmentKeyboardBinding? = null
    private val ui get() = _ui!!

    private var keysSheet: Sheet? = null

    /** Modifier bits armed for the next key, cleared after it is sent. */
    private var modifiers = 0

    /** While true, the next send presses Enter, then search mode ends. */
    private var searchMode = false

    /** Sent this session, newest first. Memory only; never stored. */
    private val recent = ArrayDeque<String>()

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
            repeat(liveBaseline.length - common) {
                service.typeKey(HidReports.MOD_NONE, HidReports.KEY_BACKSPACE)
            }
            val added = now.substring(common)
            if (added.isNotEmpty()) service.typeText(added) { _, _ -> }
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
            submit()
        }

        // The phone keyboard's action key also presses Enter on the device
        // (per the Enter setting), so a search box or password field submits.
        // The Send button types without Enter, for fields that should not.
        ui.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
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
        ui.chipKeys.setOnClickListener { showKeys() }

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
        val prefs = app.prefs
        ui.modsRow.isVisible = prefs.showModifiers
        ui.fkeysScroll.isVisible = prefs.showFunctionKeys
        val scale = ControlSizing.scale(requireContext())
        listOf(ui.esc, ui.up, ui.backspace, ui.tab, ui.left, ui.down, ui.right, ui.enter, ui.space, ui.home)
            .forEach { it.updateLayoutParams { height = (dp(56) * scale).roundToInt() } }
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
    private fun requireProKeys(): Boolean {
        val context = requireContext()
        if (Features.isPro(context)) return true
        ProActivity.open(context, Features.Pro.PRO_KEYS)
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
        ui.send.setIconResource(
            if (live) R.drawable.ic_arrow_elbow_down_left else R.drawable.ic_paper_plane_right
        )
        ui.send.contentDescription = getString(if (live) R.string.key_enter else R.string.keyboard_send)
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

        remember(text)
        service.typeText(if (withEnter) text + "\n" else text) { _, skipped ->
            activity?.runOnUiThread {
                if (_ui == null) return@runOnUiThread
                clearField()
                endSearch()
                if (skipped > 0) {
                    Toast.makeText(requireContext(), getString(R.string.chars_skipped, skipped), Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                service.typeText(text) { _, _ -> }
                remember(text)
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

    /** Pro keys: navigation and shortcuts a phone keyboard has no way to send. */
    private fun showKeys() {
        val context = requireContext()
        if (!Features.isPro(context)) {
            ProActivity.open(context, Features.Pro.PRO_KEYS)
            return
        }
        keysSheet?.dismiss()
        val sheet = Sheet(context)
            .title(getString(R.string.keys_title))
            .subtitle(getString(R.string.keys_body))
        val groups = listOf(
            R.string.keys_group_editing to listOf("line_start", "line_end", "page_up", "page_down", "delete", "select_all"),
            R.string.keys_group_shortcuts to listOf("copy", "cut", "paste", "undo", "zoom_in", "zoom_out"),
            R.string.keys_group_system to listOf("app_switch", "close_window", "show_desktop", "start_menu", "browser_back", "browser_forward")
        )
        groups.forEach { (title, ids) ->
            sheet.content.addView(TextView(context).apply {
                setTextAppearance(R.style.Text_Overline)
                text = getString(title)
                setPadding(dp(4), dp(16), 0, dp(8))
            })
            val grid = GridLayout(context).apply { columnCount = 3 }
            ids.mapNotNull { Actions.byId(it) }.forEachIndexed { i, named ->
                val key = layoutInflater.inflate(R.layout.ui_key_small, grid, false) as MaterialButton
                key.text = Actions.label(context, named)
                key.layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f)
                ).apply {
                    width = 0
                    height = dp(48)
                    if (i % 3 > 0) marginStart = dp(8)
                    if (i >= 3) topMargin = dp(8)
                }
                key.setOnClickListener {
                    Haptics.tick(it)
                    perform(named.action)
                }
                grid.addView(key)
            }
            sheet.content.addView(grid)
        }
        sheet.primary(getString(R.string.done)) { true }
        keysSheet = sheet.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        keysSheet?.dismiss()
        keysSheet = null
        app.stopObservingEntitlement(entitlementObserver)
        _ui = null
    }

    private companion object {
        const val MAX_RECENT = 12
        const val CLIPBOARD_PREVIEW = 400
    }
}
