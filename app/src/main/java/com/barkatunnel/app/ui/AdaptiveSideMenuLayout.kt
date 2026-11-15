package com.barkatunnel.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Keep the usual drawer on tall screens, compact its spacing on shorter ones.
 * Scrolling remains an accessibility fallback when enlarged text cannot fit. */
class AdaptiveSideMenuLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val header = getChildAt(0) as LinearLayout
        header.isBaselineAligned = false
        val scroll = getChildAt(1) as ScrollView
        val items = scroll.getChildAt(0) as AdaptiveMenuContent
        val compact = MeasureSpec.getSize(heightMeasureSpec) < dp(780)
        val heading = header.getChildAt(1) as LinearLayout
        for (i in 0 until heading.childCount)
            (heading.getChildAt(i) as? TextView)?.includeFontPadding = true
        (heading.getChildAt(0) as TextView).textSize = if (compact) 16f else 19f
        (heading.getChildAt(1) as TextView).textSize = if (compact) 11f else 12f
        (heading.getChildAt(1).layoutParams as LayoutParams).topMargin = dp(if (compact) 3 else 5)
        header.minimumHeight = dp(if (compact) 48 else 96)
        header.setPadding(dp(12), dp(if (compact) 0 else 12), dp(12), dp(if (compact) 0 else 12))
        items.setPadding(dp(12), dp(if (compact) 0 else 20), dp(12), dp(if (compact) 0 else 22))
        for (i in 0 until items.childCount) {
            val child = items.getChildAt(i)
            if (child !is TextView || child.visibility == View.GONE) continue
            child.includeFontPadding = true
            val section = child.id == View.NO_ID
            val params = child.layoutParams as LayoutParams
            params.height = if (section && !compact) dp(40) else LayoutParams.WRAP_CONTENT
            params.bottomMargin = dp(if (compact || section) 0 else 3)
            child.minimumHeight = if (section) dp(if (compact) 14 else 0) else dp(if (compact) 48 else 56)
            child.minHeight = child.minimumHeight
            child.setPadding(dp(if (section) 8 else 10),
                dp(if (compact) 0 else if (section) 0 else 12),
                dp(8), dp(if (compact) 0 else if (section) 7 else 12))
            if (section) child.textSize = if (compact) 10f else 11f
            else {
                child.textSize = 16f
                child.compoundDrawablePadding = dp(if (compact) 12 else 18)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (compact) {
            val rows = (0 until items.childCount).mapNotNull { index ->
                (items.getChildAt(index) as? TextView)?.takeIf { it.visibility != View.GONE && it.id != View.NO_ID }
            }
            val contentHeight = items.paddingTop + items.paddingBottom + (0 until items.childCount).sumOf { index ->
                val child = items.getChildAt(index)
                if (child.visibility == View.GONE) 0 else {
                    val params = child.layoutParams as LayoutParams
                    child.measuredHeight + params.topMargin + params.bottomMargin
                }
            }
            val spare = (scroll.measuredHeight - contentHeight).coerceAtLeast(0)
            if (rows.isNotEmpty() && spare > 0) {
                // Fill the available height evenly instead of leaving an empty
                // block below the last entry on medium-height phones.
                val extraPerRow = spare / rows.size
                rows.forEach { row -> row.minimumHeight = row.measuredHeight + extraPerRow }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        if (items.measuredHeight > scroll.measuredHeight) {
            // Preserve labels and 48dp touch targets on short, enlarged-text screens.
            (heading.getChildAt(1).layoutParams as LayoutParams).topMargin = 0
            for (i in 0 until heading.childCount)
                (heading.getChildAt(i) as? TextView)?.includeFontPadding = false
            for (i in 0 until items.childCount)
                (items.getChildAt(i) as? TextView)?.includeFontPadding = false
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
