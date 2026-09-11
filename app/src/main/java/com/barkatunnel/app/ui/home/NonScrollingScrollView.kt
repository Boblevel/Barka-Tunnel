package com.barkatunnel.app.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView

class NonScrollingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(ev: MotionEvent): Boolean = false

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = false

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val child = getChildAt(0) ?: return
        val availableWidth = measuredWidth - paddingLeft - paddingRight
        val availableHeight = measuredHeight - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        // Reflow at the logical width that will fill the viewport after fitting
        // the height. Scaling a narrow child alone creates large side gutters.
        var logicalWidth = availableWidth
        repeat(4) {
            child.measure(
                MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val scale = minOf(1f, availableHeight.toFloat() / child.measuredHeight.coerceAtLeast(1))
            val fittedWidth = kotlin.math.ceil(availableWidth / scale.toDouble()).toInt()
            if (fittedWidth == logicalWidth) return
            logicalWidth = fittedWidth
        }
        child.measure(
            MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        fitContentToViewport()
    }

    private fun fitContentToViewport() {
        val child = getChildAt(0) ?: return
        if (width <= 0 || height <= 0 || child.width <= 0 || child.height <= 0) return

        child.scaleX = 1f
        child.scaleY = 1f

        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return

        val scale = minOf(
            1f,
            availableWidth / child.width.toFloat(),
            availableHeight / child.height.toFloat()
        )

        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        super.scrollTo(0, 0)
    }
}
