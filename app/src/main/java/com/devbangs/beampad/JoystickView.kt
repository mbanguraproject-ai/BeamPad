package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
 *
 * [floating]: the base centres where the thumb lands, so the stick is
 * wherever the thumb is instead of where it has to be found. A double tap
 * clicks the stick (L3 / R3) and holds the click while the thumb stays
 * down, so it can be clicked and pushed at once. The first touch must be
 * a tap, so quick pushes of the stick never click it by accident.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The stick moved: x and y in -1..1; (0, 0) when let go. */
    var onMove: ((x: Float, y: Float) -> Unit)? = null

    /** The stick was clicked (true) or the click let go (false). */
    var onClick: ((down: Boolean) -> Unit)? = null

    /** The base centres where the thumb lands. */
    var floating = false

    /** A thumb is on the stick. */
    var isActive = false
        private set

    private val density = resources.displayMetrics.density

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.bpSurfaceHigh)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = context.themeColor(R.attr.bpStrokeStrong)
    }
    /** A faint ring where the knob's edge reaches at full tilt. */
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = context.themeColor(R.attr.bpStroke)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.bpAccent)
    }

    /** The knob's concave top, where the thumb sits. */
    private val dimplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = context.themeColor(R.attr.bpOnAccent) and 0x00FFFFFF or (0x4D shl 24)
    }
    private val clickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = context.themeColor(R.attr.bpText)
    }

    /** Where the base is drawn: the view's centre, or where the thumb landed. */
    private var centerX = 0f
    private var centerY = 0f

    /** The knob's offset from the base's centre, in pixels. */
    private var knobX = 0f
    private var knobY = 0f

    private var clicked = false
    private var lastUpAt = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** This touch, so far: when it began, where, and whether it has moved like a push. */
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var pushed = false

    private val radius: Float
        get() = min(width, height).coerceAtMost((MAX_BASE_DP * density * 2).toInt()) / 2f - ringPaint.strokeWidth
    private val knobRadius: Float get() = radius * KNOB
    private val travel: Float get() = (radius - knobRadius).coerceAtLeast(1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isActive) recenter()
    }

    private fun recenter() {
        centerX = width / 2f
        centerY = height / 2f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, radius, basePaint)
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
        canvas.drawCircle(centerX, centerY, radius * GUIDE, guidePaint)
        canvas.drawCircle(centerX + knobX, centerY + knobY, knobRadius, knobPaint)
        canvas.drawCircle(centerX + knobX, centerY + knobY, knobRadius * DIMPLE, dimplePaint)
        if (clicked) canvas.drawCircle(centerX + knobX, centerY + knobY, knobRadius - clickPaint.strokeWidth, clickPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                requestUnbufferedDispatch(event)
                isActive = true
                val now = SystemClock.uptimeMillis()
                downAt = now
                downX = event.x
                downY = event.y
                pushed = false
                val second = now - lastUpAt < DOUBLE_TAP_MS &&
                    hypot(event.x - lastUpX, event.y - lastUpY) < doubleTapSlop
                if (floating) {
                    // The base goes where the thumb is, kept whole inside the view.
                    centerX = event.x.coerceIn(radius, (width - radius).coerceAtLeast(radius))
                    centerY = event.y.coerceIn(radius, (height - radius).coerceAtLeast(radius))
                }
                if (second) {
                    clicked = true
                    Haptics.tick(this)
                    onClick?.invoke(true)
                }
                follow(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > touchSlop) pushed = true
                follow(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isActive = false
                knobX = 0f
                knobY = 0f
                if (clicked) {
                    clicked = false
                    onClick?.invoke(false)
                    lastUpAt = 0L
                } else {
                    // Only a tap (short, barely moved) can start a click: two
                    // quick pushes of the stick in a game never click it.
                    val now = SystemClock.uptimeMillis()
                    val tap = event.actionMasked == MotionEvent.ACTION_UP && !pushed && now - downAt < TAP_MS
                    lastUpAt = if (tap) now else 0L
                    lastUpX = event.x
                    lastUpY = event.y
                }
                recenter()
                invalidate()
                onMove?.invoke(0f, 0f)
            }
        }
        return true
    }

    private fun follow(x: Float, y: Float) {
        var dx = x - centerX
        var dy = y - centerY
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

        /** The guide ring's and the knob's dimple's radii, as shares of the base's and the knob's. */
        const val GUIDE = 0.72f
        const val DIMPLE = 0.62f

        /** Below this share of full travel the stick reads as centred. */
        const val DEAD_ZONE = 0.08f

        /** The base's largest radius, so a wide stick area still has a thumb-sized stick. */
        const val MAX_BASE_DP = 64

        /** A second touch this soon after the first lift is a click. */
        const val DOUBLE_TAP_MS = 280L

        /** The first touch of a click is a tap: lifted within this long. */
        const val TAP_MS = 200L
    }
}
