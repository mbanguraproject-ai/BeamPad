package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Touch surface producing relative pointer movement.
 *
 * One finger drags the pointer, a short tap left-clicks, two fingers scroll,
 * and a two-finger tap right-clicks.
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((dx: Int, dy: Int) -> Unit)? = null
    var onScroll: ((amount: Int) -> Unit)? = null
    var onClick: ((button: Byte) -> Unit)? = null

    /** Pointer travel per unit of finger travel. */
    var sensitivity: Float = 1.6f

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var travelled = 0f
    private var pointerCount = 0
    private var scrollAccum = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downTime = System.currentTimeMillis()
                travelled = 0f
                pointerCount = 1
                scrollAccum = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                travelled += abs(dx) + abs(dy)

                if (event.pointerCount >= 2) {
                    scrollAccum += dy
                    val steps = (scrollAccum / SCROLL_STEP).toInt()
                    if (steps != 0) {
                        onScroll?.invoke(steps)
                        scrollAccum -= steps * SCROLL_STEP
                    }
                } else {
                    onMove?.invoke(
                        (dx * sensitivity).toInt(),
                        (dy * sensitivity).toInt()
                    )
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP -> {
                val quick = System.currentTimeMillis() - downTime < tapTimeout * 2
                if (quick && travelled < touchSlop) {
                    val button = if (pointerCount >= 2) HidReports.BUTTON_RIGHT
                    else HidReports.BUTTON_LEFT
                    onClick?.invoke(button)
                }
                pointerCount = 0
            }
        }
        return true
    }

    private companion object {
        /** Finger pixels per wheel notch. Lower scrolls faster. */
        const val SCROLL_STEP = 28f
    }
}
