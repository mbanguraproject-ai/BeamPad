package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Touch surface producing relative pointer input.
 *
 * - One finger moves the pointer, with optional acceleration and a
 *   precision mode for small targets. Sub-pixel movement is carried over
 *   rather than dropped, so slow, careful movement still arrives.
 * - Tap clicks (when tap-to-click is on); two-finger tap right-clicks;
 *   three-finger tap middle-clicks.
 * - Tap, then touch again and move: drags with the left button held.
 * - Two fingers scroll; spreading or pinching them zooms instead.
 * - Three-finger swipes run the user's gesture actions.
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((dx: Int, dy: Int) -> Unit)? = null
    var onScroll: ((amount: Int) -> Unit)? = null
    var onClick: ((button: Byte) -> Unit)? = null

    /** Left button down/up for tap-and-drag. */
    var onDrag: ((down: Boolean) -> Unit)? = null

    /** +1 to zoom in, -1 to zoom out, per pinch step. */
    var onZoom: ((Int) -> Unit)? = null

    /** A completed three-finger swipe. */
    var onSwipe: ((Prefs.SwipeDirection) -> Unit)? = null

    /** Fires true while a finger is down, false when it lifts. */
    var onTouchActive: ((Boolean) -> Unit)? = null

    /** Pointer travel per unit of finger travel. */
    var sensitivity: Float = Prefs.DEFAULT_POINTER_SPEED

    /** 0 = linear, 1 = strong acceleration on fast flicks. */
    var acceleration: Float = 0f

    /** Wheel notches per unit of finger travel, relative to the default. */
    var scrollSpeed: Float = 1f

    /** Flips two-finger scrolling for people used to the other direction. */
    var reverseScroll: Boolean = false

    var tapToClick: Boolean = true
    var pinchToZoom: Boolean = false
    var gesturesEnabled: Boolean = false

    /** Slows the pointer for small targets on a TV or desktop. */
    var precision: Boolean = false

    var touchResponse: Prefs.TouchResponse = Prefs.TouchResponse.NORMAL

    private val baseSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val baseTapTimeout = ViewConfiguration.getTapTimeout().toLong() * 2
    private val density = resources.displayMetrics.density

    private val slop get() = baseSlop * touchResponse.slopScale
    private val tapTimeout get() = (baseTapTimeout * touchResponse.tapWindowScale).toLong()

    private enum class TwoFinger { UNDECIDED, SCROLL, ZOOM }

    private var downTime = 0L
    private var maxPointers = 0
    private var travelled = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var remX = 0f
    private var remY = 0f

    private var twoFinger = TwoFinger.UNDECIDED
    private var scrollAccum = 0f
    private var startSpan = 0f
    private var lastZoomSpan = 0f

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeFired = false

    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var dragCandidate = false
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val now = SystemClock.uptimeMillis()
                downTime = now
                maxPointers = 1
                travelled = 0f
                remX = 0f
                remY = 0f
                resetCentroid(event)
                lastMoveTime = now
                // A second touch soon after a tap, close to it, may become a drag.
                dragCandidate = now - lastTapUp < DOUBLE_TAP_MS &&
                    hypot(event.x - lastTapX, event.y - lastTapY) < 48 * density
                onTouchActive?.invoke(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, event.pointerCount)
                resetCentroid(event)
                if (event.pointerCount == 2) {
                    twoFinger = TwoFinger.UNDECIDED
                    scrollAccum = 0f
                    startSpan = span(event)
                    lastZoomSpan = startSpan
                }
                if (event.pointerCount == 3) {
                    swipeStartX = lastX
                    swipeStartY = lastY
                    swipeFired = false
                }
                endDrag()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Recompute without the lifted finger so the centroid does not jump.
                resetCentroid(event, excluding = event.actionIndex)
            }

            MotionEvent.ACTION_MOVE -> handleMove(event)

            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val quick = now - downTime < tapTimeout
                if (dragging) {
                    endDrag()
                } else if (quick && travelled < slop * 1.5f) {
                    when {
                        maxPointers >= 3 -> onClick?.invoke(HidReports.BUTTON_MIDDLE)
                        maxPointers == 2 -> onClick?.invoke(HidReports.BUTTON_RIGHT)
                        tapToClick -> {
                            onClick?.invoke(HidReports.BUTTON_LEFT)
                            lastTapUp = now
                            lastTapX = event.x
                            lastTapY = event.y
                        }
                    }
                }
                dragCandidate = false
                onTouchActive?.invoke(false)
            }

            MotionEvent.ACTION_CANCEL -> {
                endDrag()
                dragCandidate = false
                onTouchActive?.invoke(false)
            }
        }
        return true
    }

    private fun handleMove(event: MotionEvent) {
        val (cx, cy) = centroid(event)
        val dx = cx - lastX
        val dy = cy - lastY
        lastX = cx
        lastY = cy
        travelled += abs(dx) + abs(dy)
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastMoveTime).coerceAtLeast(1)
        lastMoveTime = now

        when (event.pointerCount) {
            1 -> {
                if (maxPointers > 1) return // the tail of a multi-finger gesture
                if (dragCandidate && !dragging && travelled > slop) {
                    dragging = true
                    onDrag?.invoke(true)
                }
                movePointer(dx, dy, dt)
            }
            2 -> twoFingers(event, dy)
            else -> threeFingers(cx, cy)
        }
    }

    private fun movePointer(dx: Float, dy: Float, dt: Long) {
        // Speed in dp per millisecond decides how much acceleration applies.
        val speed = hypot(dx, dy) / density / dt
        val boost = 1f + acceleration * ((speed - 0.25f) / 0.9f).coerceIn(0f, 2f)
        val factor = sensitivity * boost * (if (precision) PRECISION_FACTOR else 1f)
        val fx = dx * factor + remX
        val fy = dy * factor + remY
        val ix = fx.toInt()
        val iy = fy.toInt()
        remX = fx - ix
        remY = fy - iy
        if (ix != 0 || iy != 0) onMove?.invoke(ix, iy)
    }

    private fun twoFingers(event: MotionEvent, dy: Float) {
        val currentSpan = span(event)
        if (twoFinger == TwoFinger.UNDECIDED) {
            val spread = abs(currentSpan - startSpan)
            if (pinchToZoom && spread > slop * 2 && spread > travelled * 0.8f) {
                twoFinger = TwoFinger.ZOOM
                lastZoomSpan = currentSpan
            } else if (travelled > slop) {
                twoFinger = TwoFinger.SCROLL
            }
        }
        when (twoFinger) {
            TwoFinger.SCROLL -> {
                val step = SCROLL_STEP_DP * density / scrollSpeed.coerceAtLeast(0.1f)
                scrollAccum += dy
                val steps = (scrollAccum / step).toInt()
                if (steps != 0) {
                    onScroll?.invoke(if (reverseScroll) -steps else steps)
                    scrollAccum -= steps * step
                }
            }
            TwoFinger.ZOOM -> {
                val step = ZOOM_STEP_DP * density
                while (currentSpan - lastZoomSpan > step) {
                    lastZoomSpan += step
                    onZoom?.invoke(1)
                }
                while (lastZoomSpan - currentSpan > step) {
                    lastZoomSpan -= step
                    onZoom?.invoke(-1)
                }
            }
            TwoFinger.UNDECIDED -> Unit
        }
    }

    private fun threeFingers(cx: Float, cy: Float) {
        if (!gesturesEnabled || swipeFired) return
        val dx = cx - swipeStartX
        val dy = cy - swipeStartY
        val min = SWIPE_MIN_DP * density
        if (abs(dx) < min && abs(dy) < min) return
        swipeFired = true
        val direction = if (abs(dx) > abs(dy)) {
            if (dx > 0) Prefs.SwipeDirection.RIGHT else Prefs.SwipeDirection.LEFT
        } else {
            if (dy > 0) Prefs.SwipeDirection.DOWN else Prefs.SwipeDirection.UP
        }
        onSwipe?.invoke(direction)
    }

    private fun endDrag() {
        if (dragging) {
            dragging = false
            onDrag?.invoke(false)
        }
    }

    private fun resetCentroid(event: MotionEvent, excluding: Int = -1) {
        val (x, y) = centroid(event, excluding)
        lastX = x
        lastY = y
    }

    private fun centroid(event: MotionEvent, excluding: Int = -1): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == excluding) continue
            sx += event.getX(i)
            sy += event.getY(i)
            n++
        }
        return if (n == 0) event.x to event.y else (sx / n) to (sy / n)
    }

    private fun span(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f
        else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private companion object {
        const val DOUBLE_TAP_MS = 260L
        const val PRECISION_FACTOR = 0.35f

        /** Finger dp per wheel notch. Lower scrolls faster. */
        const val SCROLL_STEP_DP = 10f
        const val ZOOM_STEP_DP = 40f
        const val SWIPE_MIN_DP = 60f
    }
}
