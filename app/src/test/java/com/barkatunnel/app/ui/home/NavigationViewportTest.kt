package com.barkatunnel.app.ui.home

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "fr-port-mdpi")
class NavigationViewportTest {
    @Test fun drawerHeaderStaysFixedWhileEveryOptionRemainsReachable() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        try {
            for (width in listOf(288, 324, 360)) {
                val root = activity.layoutInflater.inflate(R.layout.dialog_side_menu, null) as ViewGroup
                val header = root.getChildAt(0)
                val scroll = root.getChildAt(1) as ScrollView
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 480)
                val top = header.top
                scroll.scrollTo(0, 10000)
                assertEquals(top, header.top)
                assertEquals(0, root.scrollY)
                assertEquals(View.OVER_SCROLL_NEVER, scroll.overScrollMode)
                assertFalse(scroll.isVerticalScrollBarEnabled)
                assertTrue(scroll.height > 0)
                assertTrue(scroll.scrollY > 0)
                assertEquals(width, header.width)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun settingsGuideAndNetworkRowsInflateAndKeepTheirControls() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        try {
            for (width in listOf(320, 390, 600)) {
                for (layout in listOf(R.layout.activity_settings, R.layout.activity_guide, R.layout.dialog_network_selection)) {
                    val root = activity.layoutInflater.inflate(layout, null)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, 640)
                    if (layout == R.layout.activity_settings) {
                        val shop = root.findViewById<TextView>(R.id.shopButton)
                        assertEquals("VISITEZ NOTRE BOUTIQUE 🌟🌟🌟", shop.text.toString())
                        assertTrue(shop.height >= 76)
                        assertTrue(shop.width <= width)
                    }
                    if (layout == R.layout.dialog_network_selection) {
                        for (id in listOf(R.id.optionGlobalCheck, R.id.optionTiktokCheck, R.id.optionMoovCheck, R.id.optionOrangeCheck, R.id.optionTelecelCheck)) {
                            assertNotNull(root.findViewById<ImageView>(id).drawable)
                        }
                    }
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
