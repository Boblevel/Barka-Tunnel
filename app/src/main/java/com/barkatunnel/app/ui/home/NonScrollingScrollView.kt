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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post { fitContentToViewport() }
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

        child.pivotX = child.width / 2f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        super.scrollTo(0, 0)
    }
}
