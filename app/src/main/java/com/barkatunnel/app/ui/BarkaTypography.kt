package com.barkatunnel.app.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.barkatunnel.app.R

/** Bundled fonts keep OEM font substitutions from changing layout metrics. */
object BarkaTypography {
    fun apply(root: View) {
        val regular = ResourcesCompat.getFont(root.context, R.font.barka_regular) ?: return
        val bold = ResourcesCompat.getFont(root.context, R.font.barka_bold) ?: regular
        fun visit(view: View) {
            if (view is TextView && view.typeface != Typeface.MONOSPACE) {
                val style = view.typeface?.style ?: Typeface.NORMAL
                view.setTypeface(if (style and Typeface.BOLD != 0) bold else regular, style)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }
}
