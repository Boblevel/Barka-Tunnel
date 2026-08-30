package com.barkatunnel.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * Preserves the reference size of text, buttons and icons on every screen.
 * Android only enables vertical movement when the content is genuinely taller
 * than the available viewport, instead of shrinking the complete page.
 */
class AdaptiveNoScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
}
