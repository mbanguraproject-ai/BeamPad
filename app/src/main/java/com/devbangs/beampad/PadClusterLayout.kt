package com.devbangs.beampad

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Two of the gamepad's controls in one column, offset as on an Xbox pad:
 * one in a top corner and the other in the opposite bottom corner (each
 * child's layout_gravity), so the left stick sits above and outside the
 * D-pad, and A B X Y above and outside the right stick.
 *
 * Both are measured as the same square, as large as the column allows up
 * to a thumb's reach: one above the other, or overlapping a little where
 * the column is wide and short, since a stick's round base, the D-pad's
 * cross and the A B X Y diamond all leave their corners empty. Where the
 * column has room to spare (a tablet), the pair stays together, centred.
 *
 * Children added later get touches first, so the D-pad and A B X Y come
 * after the sticks: where their squares overlap, their empty corners let a
 * touch through to the stick below.
 */
class PadClusterLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val gap = resources.getDimensionPixelSize(R.dimen.gap)
    private val maxSide = (MAX_SIDE_DP * resources.displayMetrics.density).toInt()
    private val group = Rect()
    private val placed = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val stacked = min(w, (h - gap) / 2)
        val overlapping = min((w * OVERLAP_WIDTH).toInt(), (h * OVERLAP_HEIGHT).toInt())
        val side = max(stacked, overlapping).coerceIn(0, max(0, minOf(w, h, maxSide)))
        val square = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(square, square)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val side = getChildAt(0)?.measuredWidth ?: return
        val w = right - left - paddingLeft - paddingRight
        val h = bottom - top - paddingTop - paddingBottom
        // The pair's own box: the whole column on a phone, centred in it
        // where there is more room than the pair needs.
        val groupWidth = min(w, (side * GROUP_WIDTH).toInt())
        val groupHeight = min(h, side * 2 + gap)
        val groupLeft = paddingLeft + (w - groupWidth) / 2
        val groupTop = paddingTop + (h - groupHeight) / 2
        group.set(groupLeft, groupTop, groupLeft + groupWidth, groupTop + groupHeight)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val gravity = (child.layoutParams as LayoutParams).gravity
                .takeIf { it != LayoutParams.UNSPECIFIED_GRAVITY } ?: (Gravity.TOP or Gravity.START)
            Gravity.apply(gravity, child.measuredWidth, child.measuredHeight, group, placed, layoutDirection)
            child.layout(placed.left, placed.top, placed.right, placed.bottom)
        }
    }

    private companion object {
        /**
         * Overlapping, each square takes up to these shares of the column:
         * enough apart that a stick's base never meets the cross or the
         * diamond beside it.
         */
        const val OVERLAP_WIDTH = 0.62f
        const val OVERLAP_HEIGHT = 0.58f

        /** The largest square: a thumb's reach, as the D-pad's and the sticks' own caps. */
        const val MAX_SIDE_DP = 200

        /** With room to spare, the pair's width as a multiple of one square: offset, as on a pad. */
        const val GROUP_WIDTH = 1.6f
    }
}
