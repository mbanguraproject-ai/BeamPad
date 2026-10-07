package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A scroll wheel: drag along it to scroll, tap it to middle-click. The
 * ribs move with the finger so the wheel visibly turns, and each notch
 * ticks, which is what makes a wheel feel precise rather than floaty.
 */
class ScrollStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Wheel notches; positive scrolls up. A finger moving down rolls the
     * wheel toward the user, which scrolls down, as on a real mouse.
     */
    var onScroll: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var reverse = false
    var speed = 1f

    private val density = resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpKey) }
    private val pressedBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpKeyPressed) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = context.themeColor(R.attr.bpKeyStroke)
    }
    private val rib = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.bpStrokeStrong)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private var phase = 0f
    private var lastY = 0f
    private var accum = 0f
    private var travelled = 0f
    private var down = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f
        rect.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density)
        canvas.drawRoundRect(rect, r, r, if (down) pressedBody else body)
        canvas.drawRoundRect(rect, r, r, edge)

        // Ribs across the middle stretch, shifted by the drag phase.
        val gap = RIB_GAP_DP * density
        val top = r
        val bottom = h - r
        val inset = w * 0.3f
        var y = top + (phase % gap + gap) % gap
        while (y < bottom) {
            // Fade ribs toward the ends so the wheel reads as curved.
            val t = 1f - abs((y - h / 2f) / (h / 2f - r))
            rib.alpha = (60 + 160 * t.coerceIn(0f, 1f)).roundToInt()
            canvas.drawLine(inset, y, w - inset, y, rib)
            y += gap
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastY = event.y
                accum = 0f
                travelled = 0f
                down = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                lastY = event.y
                travelled += abs(dy)
                phase += dy
                accum += dy
                val notch = NOTCH_DP * density / speed.coerceAtLeast(0.1f)
                while (abs(accum) >= notch) {
                    val fingerDown = accum > 0
                    accum -= if (fingerDown) notch else -notch
                    Haptics.tick(this)
                    val notches = if (fingerDown) -1 else 1
                    onScroll?.invoke(if (reverse) -notches else notches)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                down = false
                if (travelled < 6 * density) {
                    performClick()
                    onTap?.invoke()
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                down = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val RIB_GAP_DP = 9f
        const val NOTCH_DP = 18f
    }
}
