package com.devbangs.beampad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.max
import kotlin.math.min

/**
 * A full PC keyboard drawn as one view: number row, letters, punctuation,
 * modifiers, and the navigation block, every row ten units wide so the
 * columns line up like a real keyboard.
 *
 * Several fingers at once (hold Ctrl with one thumb, tap C with the other).
 * Modifiers are one-shot: tap Shift, then a letter; tap twice quickly to
 * lock. A modifier held down while another key is pressed applies to that
 * key only. Backspace, Delete and the arrows repeat while held, keeping the
 * modifiers they started with, so Shift plus a held arrow selects.
 *
 * The view only decides what was pressed; [onKey] turns it into HID.
 */
class FullKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Kind { CHAR, KEY, MOD, ENTER }

    class Key(
        val kind: Kind,
        /** Drawn label; for CHAR keys, the unshifted character. */
        val label: String,
        /** CHAR keys: the shifted character. */
        val shifted: String? = null,
        /** HID usage (CHAR, KEY, ENTER) or modifier bit (MOD). */
        val code: Int = 0,
        val units: Float = 1f,
        val icon: Int = 0,
        val repeats: Boolean = false,
        val description: String = label
    ) {
        internal val bounds = RectF()
        internal var row = 0
    }

    /** Called with the key and the modifier bits in force, Shift included. */
    var onKey: ((Key, Int) -> Unit)? = null

    private enum class ModState { OFF, ONE_SHOT, LOCKED }

    private val rows: List<List<Key>> = buildRows()
    private val keys: List<Key> = rows.flatMap { it }

    private val modStates = HashMap<Int, ModState>()
    private val modLastTap = HashMap<Int, Long>()
    /** Modifier bits whose key is held down, and whether another key was used meanwhile. */
    private val modHeld = HashMap<Int, Boolean>()

    private val pointers = SparseArray<Key>()
    private val pressed = HashSet<Key>()
    private var repeating: Key? = null
    private var repeatMods = 0

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val gap = dp(6f)
    private val radius = dp(10f)

    private val cKey = context.themeColor(R.attr.bpKey)
    private val cKeyPressed = context.themeColor(R.attr.bpKeyPressed)
    private val cKeyStroke = context.themeColor(R.attr.bpKeyStroke)
    private val cSpecial = context.themeColor(R.attr.bpSurfaceHigh)
    private val cText = context.themeColor(R.attr.bpText)
    private val cTextDim = context.themeColor(R.attr.bpTextDim)
    private val cTextFaint = context.themeColor(R.attr.bpTextFaint)
    private val cAccent = context.themeColor(R.attr.bpAccent)
    private val cAccent2 = context.themeColor(R.attr.bpAccent2)
    private val cAccentSoft = context.themeColor(R.attr.bpAccentSoft)
    private val cOnAccent = context.themeColor(R.attr.bpOnAccent)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val charPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = sp(19f)
    }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = sp(13f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = sp(28f)
    }

    private val icons = HashMap<Int, Drawable>()
    private val scratch = RectF()

    private val a11y = KeyboardAccessibility()

    /** Key height preference; the view shrinks keys to fit, never grows past this. */
    var maxKeyHeight: Float = dp(56f)
        set(value) {
            field = value
            requestLayout()
        }

    init {
        ViewCompat.setAccessibilityDelegate(this, a11y)
        isHapticFeedbackEnabled = true
    }

    // ---- Layout -----------------------------------------------------------------

    private fun buildRows(): List<List<Key>> {
        val r = context.resources
        fun char(c: Char, shifted: Char, code: Int) =
            Key(Kind.CHAR, c.toString(), shifted.toString(), code)
        fun letter(c: Char) = char(c, c.uppercaseChar(), 0x04 + (c - 'a'))

        val digits = "1234567890"
        val digitShifts = "!@#$%^&*()"
        val row1 = digits.mapIndexed { i, c ->
            char(c, digitShifts[i], if (c == '0') 0x27 else 0x1E + (c - '1'))
        }
        val row2 = "qwertyuiop".map { letter(it) }
        val row3 = "asdfghjkl".map { letter(it) } + char(';', ':', 0x33)
        val row4 = listOf(
            Key(Kind.MOD, r.getString(R.string.mod_shift), code = HidReports.MOD_LEFT_SHIFT.toInt(),
                units = 1.5f, icon = R.drawable.ic_arrow_fat_up)
        ) + "zxcvbnm".map { letter(it) } + listOf(
            Key(Kind.KEY, r.getString(R.string.act_backspace), code = HidReports.KEY_BACKSPACE.toInt(),
                units = 1.5f, icon = R.drawable.ic_backspace, repeats = true)
        )
        val row5 = listOf(
            char('`', '~', 0x35), char('-', '_', 0x2D), char('=', '+', 0x2E),
            char('[', '{', 0x2F), char(']', '}', 0x30), char('\\', '|', 0x31),
            char('\'', '"', 0x34), char(',', '<', 0x36), char('.', '>', 0x37),
            char('/', '?', 0x38)
        )
        val row6 = listOf(
            Key(Kind.MOD, r.getString(R.string.mod_ctrl), code = HidReports.MOD_LEFT_CTRL.toInt(), units = 1.25f),
            Key(Kind.MOD, r.getString(R.string.fullkb_win), code = HidReports.MOD_LEFT_META.toInt(), units = 1.25f,
                description = r.getString(R.string.mod_meta)),
            Key(Kind.MOD, r.getString(R.string.mod_alt), code = HidReports.MOD_LEFT_ALT.toInt(), units = 1.25f),
            Key(Kind.KEY, r.getString(R.string.act_space), code = HidReports.KEY_SPACE.toInt(), units = 3.25f),
            Key(Kind.KEY, r.getString(R.string.act_tab), code = HidReports.KEY_TAB.toInt(), units = 1.25f),
            Key(Kind.ENTER, r.getString(R.string.act_enter), code = HidReports.KEY_ENTER.toInt(), units = 1.75f,
                icon = R.drawable.ic_arrow_elbow_down_left)
        )
        val row7 = listOf(
            Key(Kind.KEY, r.getString(R.string.act_esc), code = HidReports.KEY_ESC.toInt(), units = 2f),
            Key(Kind.KEY, r.getString(R.string.key_home), code = HidReports.KEY_HOME.toInt(), units = 2f),
            Key(Kind.KEY, r.getString(R.string.act_up), code = HidReports.KEY_UP.toInt(), units = 2f,
                icon = R.drawable.ic_caret_up, repeats = true),
            Key(Kind.KEY, r.getString(R.string.fullkb_end), code = HidReports.KEY_END.toInt(), units = 2f),
            Key(Kind.KEY, r.getString(R.string.fullkb_del), code = HidReports.KEY_DELETE.toInt(), units = 2f,
                repeats = true, description = r.getString(R.string.act_delete))
        )
        val row8 = listOf(
            Key(Kind.KEY, r.getString(R.string.fullkb_pgup), code = HidReports.KEY_PAGE_UP.toInt(), units = 2f,
                description = r.getString(R.string.act_page_up)),
            Key(Kind.KEY, r.getString(R.string.act_left), code = HidReports.KEY_LEFT.toInt(), units = 2f,
                icon = R.drawable.ic_caret_left, repeats = true),
            Key(Kind.KEY, r.getString(R.string.act_down), code = HidReports.KEY_DOWN.toInt(), units = 2f,
                icon = R.drawable.ic_caret_down, repeats = true),
            Key(Kind.KEY, r.getString(R.string.act_right), code = HidReports.KEY_RIGHT.toInt(), units = 2f,
                icon = R.drawable.ic_caret_right, repeats = true),
            Key(Kind.KEY, r.getString(R.string.fullkb_pgdn), code = HidReports.KEY_PAGE_DOWN.toInt(), units = 2f,
                description = r.getString(R.string.act_page_down))
        )
        return listOf(row1, row2, row3, row4, row5, row6, row7, row8).also { all ->
            all.forEachIndexed { i, row -> row.forEach { it.row = i } }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = rows.size
        val wanted = (paddingTop + paddingBottom + n * dp(50f) + (n - 1) * gap).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(wanted, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val n = rows.size
        val usableH = h - paddingTop - paddingBottom - (n - 1) * gap
        val keyH = (usableH / n).coerceIn(dp(32f), maxKeyHeight)
        val total = keyH * n + (n - 1) * gap
        // Bottom-aligned: the keys stay where thumbs are on tall screens.
        var top = h - paddingBottom - total
        val unit = (w - paddingLeft - paddingRight + gap) / ROW_UNITS
        rows.forEach { row ->
            var x = paddingLeft.toFloat()
            row.forEach { key ->
                val width = key.units * unit - gap
                key.bounds.set(x, top, x + width, top + keyH)
                x += key.units * unit
            }
            top += keyH + gap
        }
        a11y.invalidateRoot()
    }

    private fun keyAt(x: Float, y: Float): Key? {
        // Gaps belong to the nearest key, so a near miss still lands.
        val half = gap / 2
        keys.firstOrNull { k ->
            x >= k.bounds.left - half && x <= k.bounds.right + half &&
                y >= k.bounds.top - half && y <= k.bounds.bottom + half
        }?.let { return it }
        if (keys.isEmpty() || y < keys.first().bounds.top - gap) return null
        val row = rows.minByOrNull { r ->
            val b = r.first().bounds
            when {
                y < b.top -> b.top - y
                y > b.bottom -> y - b.bottom
                else -> 0f
            }
        } ?: return null
        return row.minByOrNull { k ->
            when {
                x < k.bounds.left -> k.bounds.left - x
                x > k.bounds.right -> x - k.bounds.right
                else -> 0f
            }
        }
    }

    // ---- Modifiers --------------------------------------------------------------

    private fun state(bit: Int) = modStates[bit] ?: ModState.OFF

    private fun activeMods(): Int =
        modStates.entries.filter { it.value != ModState.OFF }.fold(0) { acc, e -> acc or e.key }

    private val shiftOn get() = state(HidReports.MOD_LEFT_SHIFT.toInt()) != ModState.OFF

    private fun modDown(key: Key) {
        val bit = key.code
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - (modLastTap[bit] ?: 0L) < DOUBLE_TAP_MS
        modStates[bit] = when (state(bit)) {
            ModState.OFF -> ModState.ONE_SHOT
            ModState.ONE_SHOT -> if (doubleTap) ModState.LOCKED else ModState.OFF
            ModState.LOCKED -> ModState.OFF
        }
        modLastTap[bit] = now
        modHeld[bit] = false
    }

    private fun modUp(key: Key) {
        val bit = key.code
        val usedWhileHeld = modHeld.remove(bit) == true
        if (usedWhileHeld && state(bit) == ModState.ONE_SHOT) modStates[bit] = ModState.OFF
    }

    /** After a key: one-shot modifiers end, unless still held down for more. */
    private fun consumeOneShots() {
        modStates.keys.toList().forEach { bit ->
            if (modHeld.containsKey(bit)) {
                modHeld[bit] = true
            } else if (modStates[bit] == ModState.ONE_SHOT) {
                modStates[bit] = ModState.OFF
            }
        }
    }

    /** Drops every modifier; for when the keyboard is hidden or the link drops. */
    fun clearModifiers() {
        modStates.clear()
        modHeld.clear()
        invalidate()
    }

    // ---- Touch ------------------------------------------------------------------

    /** Shown but not usable: the Pro preview behind the upgrade card. */
    var locked: Boolean = false
        set(value) {
            field = value
            if (value) {
                stopRepeat()
                pointers.clear()
                pressed.clear()
            }
            invalidate()
        }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val key = keyAt(event.getX(i), event.getY(i)) ?: return true
                pointers.put(event.getPointerId(i), key)
                press(key)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointers[id]?.let { release(it) }
                pointers.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until pointers.size()) release(pointers.valueAt(i))
                pointers.clear()
            }
        }
        return true
    }

    private fun press(key: Key) {
        pressed.add(key)
        Haptics.tick(this)
        if (key.kind == Kind.MOD) {
            modDown(key)
        } else {
            fire(key)
            if (key.repeats) startRepeat(key)
        }
        invalidate()
    }

    private fun release(key: Key) {
        pressed.remove(key)
        if (key.kind == Kind.MOD) modUp(key)
        if (repeating === key) stopRepeat()
        invalidate()
    }

    private fun fire(key: Key) {
        val mods = activeMods()
        onKey?.invoke(key, mods)
        repeatMods = mods
        consumeOneShots()
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = repeating ?: return
            onKey?.invoke(key, repeatMods)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun startRepeat(key: Key) {
        stopRepeat()
        repeating = key
        postDelayed(repeatRunnable, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        repeating = null
        removeCallbacks(repeatRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()
        pointers.clear()
        pressed.clear()
    }

    // ---- Drawing ----------------------------------------------------------------

    private fun icon(res: Int): Drawable? =
        icons[res] ?: ContextCompat.getDrawable(context, res)?.mutate()?.also { icons[res] = it }

    override fun onDraw(canvas: Canvas) {
        keys.forEach { drawKey(canvas, it) }
        // The preview bubble goes over the row above, so it is drawn last.
        keys.firstOrNull { it.kind == Kind.CHAR && it in pressed }?.let { drawPreview(canvas, it) }
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        val b = key.bounds
        val isPressed = key in pressed
        val mod = if (key.kind == Kind.MOD) state(key.code) else ModState.OFF

        val bg: Int
        val fg: Int
        when {
            key.kind == Kind.ENTER -> {
                bg = cAccent
                fg = cOnAccent
            }
            mod == ModState.LOCKED -> {
                bg = cAccent
                fg = cOnAccent
            }
            mod == ModState.ONE_SHOT -> {
                bg = cAccentSoft
                fg = cAccent2
            }
            isPressed -> {
                bg = cKeyPressed
                fg = cText
            }
            key.kind == Kind.CHAR -> {
                bg = cKey
                fg = cText
            }
            else -> {
                bg = cSpecial
                fg = cText
            }
        }

        // A hairline shadow under each key gives the rows physical depth.
        if (!isPressed && key.kind != Kind.ENTER && mod == ModState.OFF) {
            scratch.set(b.left, b.top + dp(1.5f), b.right, b.bottom + dp(1.5f))
            fill.color = cKeyStroke
            canvas.drawRoundRect(scratch, radius, radius, fill)
        }
        scratch.set(b)
        if (isPressed) scratch.offset(0f, dp(1f))
        fill.color = bg
        canvas.drawRoundRect(scratch, radius, radius, fill)
        if (key.kind == Kind.CHAR || (key.kind == Kind.KEY && mod == ModState.OFF)) {
            stroke.color = cKeyStroke
            canvas.drawRoundRect(scratch, radius, radius, stroke)
        }

        val cx = scratch.centerX()
        val cy = scratch.centerY()
        when {
            key.icon != 0 -> {
                val d = icon(key.icon) ?: return
                val s = (min(scratch.height(), scratch.width()) * 0.36f).coerceAtMost(dp(22f)).toInt()
                d.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                d.setTint(fg)
                d.draw(canvas)
            }
            key.kind == Kind.CHAR -> {
                val isLetter = key.label[0].isLetter()
                val main = if (shiftOn) key.shifted!! else key.label
                charPaint.color = fg
                charPaint.textSize = min(sp(19f), scratch.height() * 0.42f)
                canvas.drawText(main, cx, cy - (charPaint.ascent() + charPaint.descent()) / 2, charPaint)
                // Symbols show their other half small in the corner, as printed keys do.
                if (!isLetter) {
                    val other = if (shiftOn) key.label else key.shifted!!
                    hintPaint.color = cTextFaint
                    canvas.drawText(other, scratch.right - dp(8f), scratch.top + dp(12f), hintPaint)
                }
            }
            else -> {
                wordPaint.color = if (key.kind == Kind.KEY && mod == ModState.OFF && !isPressed) cTextDim else fg
                wordPaint.textSize = min(sp(13f), scratch.height() * 0.3f)
                canvas.drawText(key.label, cx, cy - (wordPaint.ascent() + wordPaint.descent()) / 2, wordPaint)
            }
        }

        // A locked modifier gets a bar under its label, like a caps lock light.
        if (mod == ModState.LOCKED) {
            fill.color = cOnAccent
            val w = dp(14f)
            scratch.set(cx - w / 2, b.bottom - dp(7f), cx + w / 2, b.bottom - dp(5f))
            canvas.drawRoundRect(scratch, dp(1f), dp(1f), fill)
        }
    }

    private fun drawPreview(canvas: Canvas, key: Key) {
        val b = key.bounds
        val w = max(b.width() * 1.35f, dp(46f))
        val h = b.height() * 1.15f
        val bottom = b.top - dp(4f)
        if (bottom - h < 0) return
        val left = max(paddingLeft.toFloat(), min(b.centerX() - w / 2, width - paddingRight - w))
        scratch.set(left, bottom - h, left + w, bottom)
        fill.color = cKeyStroke
        canvas.drawRoundRect(scratch.left, scratch.top + dp(2f), scratch.right, scratch.bottom + dp(2f), radius, radius, fill)
        fill.color = cKey
        canvas.drawRoundRect(scratch, radius, radius, fill)
        stroke.color = cKeyStroke
        canvas.drawRoundRect(scratch, radius, radius, stroke)
        val text = if (shiftOn) key.shifted!! else key.label
        previewPaint.color = cText
        canvas.drawText(
            text,
            scratch.centerX(),
            scratch.centerY() - (previewPaint.ascent() + previewPaint.descent()) / 2,
            previewPaint
        )
    }

    // ---- Accessibility ----------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun describe(key: Key): String = when (key.kind) {
        Kind.CHAR -> if (shiftOn) key.shifted!! else key.label
        else -> key.description
    }

    private inner class KeyboardAccessibility : ExploreByTouchHelper(this@FullKeyboardView) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val key = keyAt(x, y) ?: return ExploreByTouchHelper.INVALID_ID
            return keys.indexOf(key)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            keys.indices.forEach { virtualViewIds.add(it) }
        }

        @Suppress("DEPRECATION")
        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val key = keys.getOrNull(virtualViewId)
            if (key == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = describe(key)
            val r = Rect()
            key.bounds.roundOut(r)
            if (r.isEmpty) r.set(0, 0, 1, 1)
            node.setBoundsInParent(r)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.isClickable = true
            if (key.kind == Kind.MOD) {
                node.isCheckable = true
                node.isChecked = state(key.code) != ModState.OFF
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || locked) return false
            val key = keys.getOrNull(virtualViewId) ?: return false
            if (key.kind == Kind.MOD) {
                modDown(key)
                modUp(key)
            } else {
                fire(key)
            }
            invalidate()
            invalidateVirtualView(virtualViewId)
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    private companion object {
        const val ROW_UNITS = 10f
        const val DOUBLE_TAP_MS = 350L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
    }
}
