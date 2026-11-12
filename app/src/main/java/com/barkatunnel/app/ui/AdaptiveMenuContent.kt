package com.barkatunnel.app.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.barkatunnel.app.R

/** Same options and section order; use columns only when screen height is scarce. */
class AdaptiveMenuContent @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var columns = 1
    private val cells = mutableMapOf<View, Rect>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (columns == 1) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        cells.clear()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        var y = paddingTop
        val row = mutableListOf<View>()
        fun flush() {
            if (row.isEmpty()) return
            val height = row.maxOf { it.measuredHeight }
            row.forEachIndexed { column, child ->
                val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    width - paddingRight - (column + 1) * available / columns
                else paddingLeft + column * available / columns
                cells[child] = Rect(left, y, left + available / columns, y + height)
            }
            y += height
            row.clear()
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val fullWidth = child.id == View.NO_ID || child.id == R.id.menuHome
            if (fullWidth) flush()
            val params = child.layoutParams
            child.measure(MeasureSpec.makeMeasureSpec(if (fullWidth) available else available / columns, MeasureSpec.EXACTLY),
                if (params.height >= 0) MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (fullWidth) {
                cells[child] = Rect(paddingLeft, y, width - paddingRight, y + child.measuredHeight)
                y += child.measuredHeight
            } else {
                row.add(child)
                if (row.size == columns) flush()
            }
        }
        flush()
        setMeasuredDimension(width, resolveSize(y + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (columns == 1) { super.onLayout(changed, left, top, right, bottom); return }
        cells.forEach { (child, bounds) -> child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom) }
    }
}
