package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A thumbstick for the gamepad: a round base and a knob that follows the
 * thumb within it and springs back to the centre when let go.
 *
 * Reports the knob's position as -1..1 on each axis (down and right are
 * positive, as a gamepad's Y and X are), with a small dead zone so a resting
 * thumb reads as centred. Touches are delivered unbuffered, as on the
 * trackpad, so the stick follows the thumb without a frame's delay.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The stick moved: x and y in -1..1; (0, 0) when let go. */
    var onMove: ((x: Float, y: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.bpSurfaceHigh)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = context.themeColor(R.attr.bpStrokeStrong)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.bpAccent)
    }

    /** The knob's offset from the centre, in pixels. */
    private var knobX = 0f
    private var knobY = 0f

    private val radius: Float get() = min(width, height) / 2f - ringPaint.strokeWidth
    private val knobRadius: Float get() = radius * KNOB
    private val travel: Float get() = (radius - knobRadius).coerceAtLeast(1f)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx + knobX, cy + knobY, knobRadius, knobPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                requestUnbufferedDispatch(event)
                follow(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> follow(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = 0f
                knobY = 0f
                invalidate()
                onMove?.invoke(0f, 0f)
            }
        }
        return true
    }

    private fun follow(x: Float, y: Float) {
        var dx = x - width / 2f
        var dy = y - height / 2f
        val distance = hypot(dx, dy)
        if (distance > travel) {
            dx = dx / distance * travel
            dy = dy / distance * travel
        }
        knobX = dx
        knobY = dy
        invalidate()
        var nx = dx / travel
        var ny = dy / travel
        if (hypot(nx, ny) < DEAD_ZONE) {
            nx = 0f
            ny = 0f
        }
        onMove?.invoke(nx, ny)
    }

    private companion object {
        /** The knob's size as a share of the base's. */
        const val KNOB = 0.42f

        /** Below this share of full travel the stick reads as centred. */
        const val DEAD_ZONE = 0.08f
    }
}
