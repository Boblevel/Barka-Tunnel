package com.barkatunnel.app.ui

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.barkatunnel.app.R

object SystemBars {
    fun apply(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = activity.getColor(R.color.barka_background)
        window.navigationBarColor = activity.getColor(R.color.barka_background)
        val light = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }

        val content = activity.findViewById<android.view.View>(android.R.id.content)
        val pageNames = setOf("SettingsActivity", "GuideActivity", "GuideSectionActivity",
            "SubscriptionActivity", "ActivationActivity", "IpFinderActivity", "SupportActivity",
            "SuggestionsActivity", "ResellerPurchaseActivity", "ConsumptionActivity", "VpnToolsActivity")
        val alignedPage = activity.javaClass.simpleName in pageNames
        if (alignedPage) {
            val root = (content as? android.view.ViewGroup)?.getChildAt(0) as? android.view.ViewGroup
            val header = if (activity.javaClass.simpleName == "VpnToolsActivity") root else root?.getChildAt(0)
            header?.setPaddingRelative(header.paddingStart,
                (8 * activity.resources.displayMetrics.density).toInt(), header.paddingEnd, header.paddingBottom)
            val container = header as? android.widget.LinearLayout
            val row = if (container?.orientation == android.widget.LinearLayout.VERTICAL) container.getChildAt(0) else container
            row?.let {
                it.minimumHeight = (56 * activity.resources.displayMetrics.density).toInt()
                it.layoutParams = it.layoutParams.apply { height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val referenceStatusBar = (24f * view.resources.displayMetrics.density).toInt()
            val topPadding = if (alignedPage) bars.top else (bars.top - referenceStatusBar).coerceAtLeast(0)
            view.setPadding(bars.left, topPadding, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
}
