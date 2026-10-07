package com.devbangs.beampad

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A relative slider for controls that only know "one step up" and "one
 * step down", like Bluetooth volume. Dragging emits a step every
 * [stepDp] of travel with a tick each time; on release the thumb springs
 * back to centre, because there is no absolute level to show. A tap on
 * either half nudges one step.
 */
class StepSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** +1 for each step right (up), -1 for each step left (down). */
    var onStep: ((Int) -> Unit)? = null

    var stepDp: Float = 22f

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpKey) }
    private val trackEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = context.themeColor(R.attr.bpKeyStroke)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpStrokeStrong) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpAccentSoft) }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.bpAccent) }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.bpTextDim)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    /** Thumb offset from centre, in pixels. */
    private var offset = 0f
    private var downX = 0f
    private var lastStepAt = 0f
    private var moved = false
    private var spring: ValueAnimator? = null
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((52 * density).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        rect.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density)
        canvas.drawRoundRect(rect, r, r, track)
        canvas.drawRoundRect(rect, r, r, trackEdge)

        val cx = w / 2f
        val cy = h / 2f

        // Travel from centre, shown as a soft fill toward the thumb.
        if (offset != 0f) {
            val left = if (offset < 0) cx + offset else cx
            val right = if (offset < 0) cx else cx + offset
            rect.set(left, cy - r + 6 * density, right, cy + r - 6 * density)
            canvas.drawRoundRect(rect, r, r, fill)
        }

        // Detents every step, fading toward the ends.
        val step = stepDp * density
        var x = step
        while (cx + x < w - r) {
            canvas.drawCircle(cx + x, cy, 1.4f * density, tick)
            canvas.drawCircle(cx - x, cy, 1.4f * density, tick)
            x += step
        }

        // Minus and plus at the ends.
        val g = 6f * density
        canvas.drawLine(r - g, cy, r + g, cy, glyph)
        canvas.drawLine(w - r - g, cy, w - r + g, cy, glyph)
        canvas.drawLine(w - r, cy - g, w - r, cy + g, glyph)

        canvas.drawCircle(cx + offset, cy, r - 7 * density, thumb)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val limit = width / 2f - height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                spring?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                lastStepAt = 0f
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val travel = (event.x - downX).coerceIn(-limit, limit)
                if (abs(travel) > 4 * density) moved = true
                offset = travel
                val step = stepDp * density
                while (travel - lastStepAt >= step) {
                    lastStepAt += step
                    emit(1)
                }
                while (lastStepAt - travel >= step) {
                    lastStepAt -= step
                    emit(-1)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    performClick()
                    emit(if (event.x >= width / 2f) 1 else -1)
                }
                springBack()
            }
            MotionEvent.ACTION_CANCEL -> springBack()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun emit(direction: Int) {
        Haptics.tick(this)
        onStep?.invoke(direction)
    }

    private fun springBack() {
        spring?.cancel()
        spring = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = Motion.duration(context, 220)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
