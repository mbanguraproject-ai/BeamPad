package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The gamepad's D-pad: one surface under the thumb, like a real D-pad's
 * rocker, instead of four separate keys. Where the thumb is picks the
 * direction, diagonals included, and sliding the thumb rolls from one
 * direction to the next without lifting, as fighting and platform games
 * expect.
 *
 * Diagonals take a narrower slice than the four directions, so a thumb
 * meaning "up" does not drift into "up and right". Reports the held
 * directions as HidReports.DPAD_* bits.
 */
class GamepadDpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The held directions changed: HidReports.DPAD_* bits, 0 when let go. */
    var onChange: ((dpad: Int) -> Unit)? = null

    private var held = 0
    private val density = resources.displayMetrics.density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpKey) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = context.themeColor(R.attr.bpKeyStroke)
    }
    private val heldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpAccentSoft) }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.themeColor(R.attr.bpText)
    }
    private val arrowHeldPaint = Paint(arrowPaint).apply { color = context.themeColor(R.attr.bpAccent) }

    /** The cross as one outline, so the arms meet without seams. */
    private val cross = Path()
    private val arrow = Path()

    init {
        contentDescription = context.getString(R.string.pad_dpad)
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val half = side(w, h) / 2f - strokePaint.strokeWidth
        val thick = side(w, h) * ARM
        val cx = w / 2f
        val cy = h / 2f
        val corner = 10 * density
        val vertical = Path().apply {
            addRoundRect(RectF(cx - thick / 2, cy - half, cx + thick / 2, cy + half), corner, corner, Path.Direction.CW)
        }
        val horizontal = Path().apply {
            addRoundRect(RectF(cx - half, cy - thick / 2, cx + half, cy + thick / 2), corner, corner, Path.Direction.CW)
        }
        cross.reset()
        cross.op(vertical, horizontal, Path.Op.UNION)
    }

    override fun onDraw(canvas: Canvas) {
        val size = side(width, height)
        val cx = width / 2f
        val cy = height / 2f
        val half = size / 2f
        val thick = size * ARM
        canvas.drawPath(cross, keyPaint)
        // A held arm lights from the hub outwards, inside the cross.
        canvas.save()
        canvas.clipPath(cross)
        DIRECTIONS.forEach { (bit, angle) ->
            if (held and bit == 0) return@forEach
            canvas.save()
            canvas.rotate(angle, cx, cy)
            canvas.drawRect(cx - thick / 2, cy - half, cx + thick / 2, cy - thick / 2, heldPaint)
            canvas.restore()
        }
        canvas.restore()
        canvas.drawPath(cross, strokePaint)
        // An arrow near each arm's end.
        DIRECTIONS.forEach { (bit, angle) ->
            canvas.save()
            canvas.rotate(angle, cx, cy)
            val tip = cy - half + thick * 0.42f
            val w = thick * 0.2f
            arrow.reset()
            arrow.moveTo(cx - w, tip + w * 0.8f)
            arrow.lineTo(cx, tip)
            arrow.lineTo(cx + w, tip + w * 0.8f)
            canvas.drawPath(arrow, if (held and bit != 0) arrowHeldPaint else arrowPaint)
            canvas.restore()
        }
    }

    /** The D-pad's size: the room it has, up to a thumb's reach on a large screen. */
    private fun side(w: Int, h: Int): Float = min(min(w, h).toFloat(), MAX_SIZE_DP * density)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                requestUnbufferedDispatch(event)
                set(directionAt(event.x, event.y))
            }
            MotionEvent.ACTION_MOVE -> set(directionAt(event.x, event.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(0)
        }
        return true
    }

    /** The directions under a point: none in the small hub, diagonals in a narrow slice. */
    private fun directionAt(x: Float, y: Float): Int {
        val dx = x - width / 2f
        val dy = y - height / 2f
        if (hypot(dx, dy) < side(width, height) * HUB) return 0
        // 0 along the horizontal, 90 along the vertical, whichever side.
        val fromHorizontal = Math.toDegrees(atan2(abs(dy), abs(dx)).toDouble())
        var bits = 0
        if (fromHorizontal < 90 - DIAGONAL_FROM) bits = bits or if (dx > 0) HidReports.DPAD_RIGHT else HidReports.DPAD_LEFT
        if (fromHorizontal > DIAGONAL_FROM) bits = bits or if (dy > 0) HidReports.DPAD_DOWN else HidReports.DPAD_UP
        return bits
    }

    private fun set(bits: Int) {
        if (bits == held) return
        // A tick for each new direction under the thumb, not for letting go.
        if (bits != 0) Haptics.tick(this)
        held = bits
        invalidate()
        onChange?.invoke(bits)
    }

    // Screen readers: a press in each direction, as four buttons would offer.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        ACCESSIBILITY.forEach { (id, label) ->
            info.addAction(AccessibilityAction(id, context.getString(label)))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val bit = when (action) {
            R.id.pad_action_up -> HidReports.DPAD_UP
            R.id.pad_action_down -> HidReports.DPAD_DOWN
            R.id.pad_action_left -> HidReports.DPAD_LEFT
            R.id.pad_action_right -> HidReports.DPAD_RIGHT
            else -> return super.performAccessibilityAction(action, arguments)
        }
        set(bit)
        postDelayed({ set(0) }, ACCESSIBILITY_PRESS_MS)
        return true
    }

    private companion object {
        /** The largest the D-pad grows, like the sticks' cap. */
        const val MAX_SIZE_DP = 180

        /** Each arm's width as a share of the D-pad's size. */
        const val ARM = 0.36f

        /** The dead hub's radius as a share of the size. */
        const val HUB = 0.09f

        /**
         * Degrees from an axis where the other direction joins in: 30
         * leaves the diagonals a 30 degree slice and the four directions 60.
         */
        const val DIAGONAL_FROM = 30.0

        const val ACCESSIBILITY_PRESS_MS = 80L

        val DIRECTIONS = listOf(
            HidReports.DPAD_UP to 0f, HidReports.DPAD_RIGHT to 90f,
            HidReports.DPAD_DOWN to 180f, HidReports.DPAD_LEFT to 270f
        )

        val ACCESSIBILITY = listOf(
            R.id.pad_action_up to R.string.act_up, R.id.pad_action_down to R.string.act_down,
            R.id.pad_action_left to R.string.act_left, R.id.pad_action_right to R.string.act_right
        )
    }
}
