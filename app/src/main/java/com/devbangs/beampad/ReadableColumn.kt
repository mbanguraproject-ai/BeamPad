package com.devbangs.beampad

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * A column that stops growing at a readable width, centred by its parent
 * (layout_gravity), so settings, lists and editors on a tablet or an
 * unfolded foldable read as a column instead of lines stretched edge to
 * edge. On phones it is an ordinary LinearLayout.
 */
class ReadableColumn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val maxWidth = resources.getDimensionPixelSize(R.dimen.readable_width)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val capped = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && size > maxWidth
        super.onMeasure(
            if (capped) MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY) else widthMeasureSpec,
            heightMeasureSpec
        )
    }
}
