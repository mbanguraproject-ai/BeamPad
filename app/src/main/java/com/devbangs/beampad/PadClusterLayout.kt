package com.devbangs.beampad

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Two of the gamepad's controls in one column, offset as on an Xbox pad:
 * one in a top corner and the other in the opposite bottom corner (each
 * child's layout_gravity), so the left stick sits above and outside the
 * D-pad, and A B X Y above and outside the right stick.
 *
 * Both are measured as the same square, as large as the column allows:
 * one above the other, or overlapping a little where the column is wide
 * and short, since a stick's round base, the D-pad's cross and the A B X Y
 * diamond all leave their corners empty.
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val stacked = min(w, (h - gap) / 2)
        val overlapping = min((w * OVERLAP_WIDTH).toInt(), (h * OVERLAP_HEIGHT).toInt())
        val side = max(stacked, overlapping).coerceIn(0, max(0, min(w, h)))
        val square = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(square, square)
        setMeasuredDimension(width, height)
    }

    private companion object {
        /**
         * Overlapping, each square takes up to these shares of the column:
         * enough apart that a stick's base never meets the cross or the
         * diamond beside it.
         */
        const val OVERLAP_WIDTH = 0.62f
        const val OVERLAP_HEIGHT = 0.58f
    }
}
