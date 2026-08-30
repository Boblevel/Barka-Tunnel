package com.barkatunnel.app.ui.home

import android.content.Context
import android.util.AttributeSet
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
}
