package com.barkatunnel.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView

/**
 * Keeps a static page fully visible without scrolling. If the page is taller or
 * wider than the available viewport, its single child is uniformly reduced so
 * the complete page remains visible on smaller Android screens.
 */
class AdaptiveNoScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(ev: MotionEvent): Boolean = false

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = false

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(0, 0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post { fitChildToViewport() }
    }

    private fun fitChildToViewport() {
        val child: View = getChildAt(0) ?: return
        if (width <= 0 || height <= 0 || child.width <= 0 || child.height <= 0) return

        child.scaleX = 1f
        child.scaleY = 1f

        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return

        val widthScale = availableWidth / child.width.toFloat()
        val heightScale = availableHeight / child.height.toFloat()
        val scale = minOf(1f, widthScale, heightScale).coerceAtLeast(MIN_SCALE)

        child.pivotX = child.width / 2f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        super.scrollTo(0, 0)
    }

    companion object {
        private const val MIN_SCALE = 0.1f
    }
}
