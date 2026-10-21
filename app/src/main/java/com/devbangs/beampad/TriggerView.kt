package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * An analog trigger (LT / RT) as a pedal: touching it presses it, and how
 * high the finger is sets how far, filling from the bottom. The top quarter
 * is a full press, so a quick tap near the label is all the way down, and
 * sliding down eases off, for throttle and brake in racing games.
 *
 * Reports 0..1: 0 only when let go, so a touch anywhere is a press.
 */
class TriggerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The trigger moved: 0 when let go, otherwise MIN_PRESS..1. */
    var onChange: ((value: Float) -> Unit)? = null

    /** LT, L2 or ZL: set from the chosen button labels. */
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private var value = 0f
    private val density = resources.displayMetrics.density
    private val corner = 14 * density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpKey) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = context.themeColor(R.attr.bpKeyStroke)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpAccent) }
    private val textColor = context.themeColor(R.attr.bpText)
    private val textOnFillColor = context.themeColor(R.attr.bpOnAccent)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    }
    private val face = RectF()
    private val fill = RectF()

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val inset = strokePaint.strokeWidth / 2
        face.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(face, corner, corner, facePaint)
        if (value > 0f) {
            fill.set(face.left, face.bottom - face.height() * value, face.right, face.bottom)
            canvas.save()
            canvas.clipRect(fill)
            canvas.drawRoundRect(face, corner, corner, fillPaint)
            canvas.restore()
        }
        canvas.drawRoundRect(face, corner, corner, strokePaint)
        textPaint.color = if (value >= LABEL_ON_FILL) textOnFillColor else textColor
        canvas.drawText(label, width / 2f, 12 * density - textPaint.ascent(), textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                requestUnbufferedDispatch(event)
                Haptics.tick(this)
                set(at(event.y))
            }
            MotionEvent.ACTION_MOVE -> set(at(event.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(0f)
        }
        return true
    }

    /** How far a finger at [y] presses: full in the top quarter, easing off below. */
    private fun at(y: Float): Float {
        if (height == 0) return 1f
        val fromBottom = (height - y) / (height * (1f - FULL_ZONE))
        return fromBottom.coerceIn(MIN_PRESS, 1f)
    }

    private fun set(next: Float) {
        if (next == value) return
        value = next
        invalidate()
        onChange?.invoke(next)
    }

    // Screen readers: a full press, as a button would give.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityAction.ACTION_CLICK)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return super.performAccessibilityAction(action, arguments)
        set(1f)
        postDelayed({ set(0f) }, ACCESSIBILITY_PRESS_MS)
        return true
    }

    private companion object {
        /** The top share of the pedal that is a full press. */
        const val FULL_ZONE = 0.25f

        /** The lightest press a touch gives, so a touch always counts. */
        const val MIN_PRESS = 0.2f

        /** From here the label sits on the fill and turns to its colour. */
        const val LABEL_ON_FILL = 0.85f

        const val ACCESSIBILITY_PRESS_MS = 80L
    }
}
