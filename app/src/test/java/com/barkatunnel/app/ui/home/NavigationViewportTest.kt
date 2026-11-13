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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class, qualifiers = "fr-port-mdpi")
class NavigationViewportTest {
    @Test fun portraitScreensShowAllMenuOptionsWithoutScrolling() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        try {
            for ((width, height) in listOf(260 to 568, 280 to 640, 324 to 800, 420 to 900, 500 to 320, 260 to 480)) {
                val root = activity.layoutInflater.inflate(R.layout.dialog_side_menu, null) as ViewGroup
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val scroll = root.getChildAt(1) as ScrollView
                scroll.scrollTo(0, 10000)
                val body = scroll.getChildAt(0) as ViewGroup
                val dimensions = (0 until body.childCount).map { body.getChildAt(it) }.joinToString { "${it.id}:${it.measuredWidth}x${it.measuredHeight}@${it.top}" }
                assertEquals("Drawer $width x $height header=${root.getChildAt(0).measuredHeight} viewport=${scroll.measuredHeight} body=${body.measuredHeight} children=$dimensions", 0, scroll.scrollY)
                for (id in listOf(R.id.menuHome, R.id.menuGuide, R.id.menuSubscription,
                    R.id.menuActivation, R.id.menuIpFinder, R.id.menuSettings, R.id.menuReseller,
                    R.id.menuSupport, R.id.menuSuggestions)) {
                    val item = root.findViewById<TextView>(id)
                    assertTrue(item.height >= 48)
                    assertTrue(item.bottom <= scroll.height)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun largerTextKeepsAllOptionsReachableWithoutScrolling() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        try { portraitScreensShowAllMenuOptionsWithoutScrolling() }
        finally { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun drawerHeaderStaysFixedWhileEveryOptionRemainsReachable() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        try {
            for (width in listOf(220, 260, 324)) {
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
                assertEquals(0, scroll.scrollY)
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
                for (layout in listOf(R.layout.activity_settings, R.layout.activity_guide, R.layout.activity_suggestions, R.layout.dialog_network_selection)) {
                    val root = activity.layoutInflater.inflate(layout, null)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, 640)
                    if (layout == R.layout.activity_settings || layout == R.layout.activity_suggestions) {
                        val container = root as ViewGroup
                        val header = container.getChildAt(0) as ViewGroup
                        val scroll = container.getChildAt(1) as ScrollView
                        val top = header.top
                        scroll.scrollTo(0, 10000)
                        assertEquals(top, header.top)
                        assertEquals(0, container.scrollY)
                        assertTrue(scroll.height > 0)
                        assertFalse(scroll.isVerticalScrollBarEnabled)
                        if (layout == R.layout.activity_settings) assertEquals(1, header.childCount)
                        else {
                            assertNotNull(root.findViewById<View>(R.id.suggestionMessage))
                            assertFalse(root.findViewById<View>(R.id.suggestionSend).isEnabled)
                        }
                    }
                    if (layout == R.layout.activity_settings) {
                        val shop = root.findViewById<TextView>(R.id.shopButton)
                        assertEquals("VISITEZ NOTRE BOUTIQUE 🛍️", shop.text.toString())
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
