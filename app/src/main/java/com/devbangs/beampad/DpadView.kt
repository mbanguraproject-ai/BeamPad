package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Circular four-way pad with a centre OK.
 *
 * Wedges rather than separate buttons: the whole circle is a target, which
 * matters when the user is aiming with a thumb while looking at the TV.
 */
class DpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Key { UP, DOWN, LEFT, RIGHT, OK }

    var onKey: ((Key) -> Unit)? = null

    private var pressed: Key? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val wedge = Path()
    private val bounds = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val colSurface = Color.parseColor("#2E141C3D")
    private val colSurfaceDim = Color.parseColor("#14121A38")
    private val colPressed = Color.parseColor("#4D4F46E5")
    private val colStroke = Color.parseColor("#3B4190")
    private val colStrokeDim = Color.parseColor("#1F2C4A")
    private val colAccent = Color.parseColor("#7C6BFF")
    private val colText = Color.parseColor("#EEF1FF")
    private val colTextDim = Color.parseColor("#4D9BA4CC")

    /** Gap between wedges, in degrees. */
    private val gapDeg = 5f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = min(
            MeasureSpec.getSize(widthSpec),
            if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED)
                MeasureSpec.getSize(widthSpec) else MeasureSpec.getSize(heightSpec)
        )
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - dp(6f)
        val inner = outer * 0.42f

        drawHalo(canvas, cx, cy, outer)

        // Wedges start at -45 so up, right, down, left sit on the diagonals.
        val keys = listOf(Key.UP, Key.RIGHT, Key.DOWN, Key.LEFT)
        keys.forEachIndexed { i, key ->
            val start = -135f + i * 90f + gapDeg / 2f
            val sweep = 90f - gapDeg
            drawWedge(canvas, cx, cy, inner, outer, start, sweep, key)
        }

        drawCentre(canvas, cx, cy, inner)
    }

    private fun drawHalo(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        // Layered rings at falling alpha: Canvas has no cheap blur here.
        val steps = listOf(dp(5f) to 0x22, dp(3f) to 0x55, dp(1f) to 0xAA)
        steps.forEach { (offset, alpha) ->
            stroke.color = colAccent
            stroke.alpha = alpha
            stroke.strokeWidth = dp(2f)
            canvas.drawCircle(cx, cy, outer + offset, stroke)
        }
        stroke.alpha = 255
    }

    private fun drawWedge(
        canvas: Canvas, cx: Float, cy: Float,
        inner: Float, outer: Float,
        start: Float, sweep: Float, key: Key
    ) {
        val down = pressed == key
        wedge.reset()
        bounds.set(cx - outer, cy - outer, cx + outer, cy + outer)
        wedge.arcTo(bounds, start, sweep, true)
        bounds.set(cx - inner, cy - inner, cx + inner, cy + inner)
        wedge.arcTo(bounds, start + sweep, -sweep, false)
        wedge.close()

        fill.color = when {
            !isEnabled -> colSurfaceDim
            down -> colPressed
            else -> colSurface
        }
        canvas.drawPath(wedge, fill)

        stroke.color = if (isEnabled && down) colAccent else if (isEnabled) colStroke else colStrokeDim
        canvas.drawPath(wedge, stroke)

        drawArrow(canvas, cx, cy, (inner + outer) / 2f, key)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, radius: Float, key: Key) {
        val s = dp(7f)
        val (ax, ay) = when (key) {
            Key.UP -> cx to cy - radius
            Key.DOWN -> cx to cy + radius
            Key.LEFT -> cx - radius to cy
            Key.RIGHT -> cx + radius to cy
            Key.OK -> return
        }
        glyph.color = if (isEnabled) colText else colTextDim

        val p = Path()
        when (key) {
            Key.UP -> { p.moveTo(ax - s, ay + s * 0.5f); p.lineTo(ax, ay - s * 0.5f); p.lineTo(ax + s, ay + s * 0.5f) }
            Key.DOWN -> { p.moveTo(ax - s, ay - s * 0.5f); p.lineTo(ax, ay + s * 0.5f); p.lineTo(ax + s, ay - s * 0.5f) }
            Key.LEFT -> { p.moveTo(ax + s * 0.5f, ay - s); p.lineTo(ax - s * 0.5f, ay); p.lineTo(ax + s * 0.5f, ay + s) }
            Key.RIGHT -> { p.moveTo(ax - s * 0.5f, ay - s); p.lineTo(ax + s * 0.5f, ay); p.lineTo(ax - s * 0.5f, ay + s) }
            Key.OK -> return
        }
        canvas.drawPath(p, glyph)
    }

    private fun drawCentre(canvas: Canvas, cx: Float, cy: Float, inner: Float) {
        val down = pressed == Key.OK
        val r = inner - dp(4f)

        fill.color = when {
            !isEnabled -> colSurfaceDim
            down -> colPressed
            else -> colSurface
        }
        canvas.drawCircle(cx, cy, r, fill)

        stroke.color = if (isEnabled && down) colAccent else if (isEnabled) colStroke else colStrokeDim
        canvas.drawCircle(cx, cy, r, stroke)

        label.color = if (isEnabled) colText else colTextDim
        label.textSize = r * 0.46f
        val baseline = cy - (label.descent() + label.ascent()) / 2f
        canvas.drawText("OK", cx, baseline, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = keyAt(event.x, event.y)
                invalidate()
                return pressed != null
            }
            MotionEvent.ACTION_UP -> {
                val hit = keyAt(event.x, event.y)
                if (hit != null && hit == pressed) {
                    performClick()
                    onKey?.invoke(hit)
                }
                pressed = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Maps a touch to a wedge, or null outside the circle or in a gap. */
    private fun keyAt(x: Float, y: Float): Key? {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - dp(6f)
        val inner = outer * 0.42f

        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)

        if (dist > outer) return null
        if (dist <= inner) return Key.OK

        // 0 degrees is right, increasing clockwise on screen.
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (deg < 0) deg += 360f

        return when {
            deg >= 225f && deg < 315f -> Key.UP
            deg >= 315f || deg < 45f -> Key.RIGHT
            deg >= 45f && deg < 135f -> Key.DOWN
            else -> Key.LEFT
        }
    }
}
