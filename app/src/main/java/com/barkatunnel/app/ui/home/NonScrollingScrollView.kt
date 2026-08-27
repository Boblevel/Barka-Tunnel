package com.barkatunnel.app.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

class NonScrollingScrollView @JvmOverloads constructor(
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
}
