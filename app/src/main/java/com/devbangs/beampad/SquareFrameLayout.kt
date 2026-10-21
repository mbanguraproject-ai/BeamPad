package com.devbangs.beampad

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Takes the room it is given and lays its children out as the largest
 * square that fits, placed by their layout gravity. For clusters that must
 * keep their shape, such as the gamepad's A B X Y, whatever the screen.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val side = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).coerceAtLeast(0)
        val square = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(square, square)
    }
}
