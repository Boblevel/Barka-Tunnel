package com.barkatunnel.app.tools

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
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
class VpnToolsViewportTest {
    @Test fun controlsRemainReachableOnPhoneTabletAndLandscape() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        try {
            for ((width, height) in listOf(320 to 480, 390 to 800, 600 to 900, 800 to 320)) {
                val root = activity.layoutInflater.inflate(R.layout.activity_vpn_tools, null) as ViewGroup
                root.findViewById<TextView>(R.id.toolTitle).setText(R.string.vpn_share_title)
                root.findViewById<TextView>(R.id.toolHelp).setText(R.string.vpn_share_help)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val scroll = root.getChildAt(1) as ScrollView
                val action = root.findViewById<View>(R.id.toolAction)
                val top = root.getChildAt(0).top
                scroll.fullScroll(View.FOCUS_DOWN)
                scroll.scrollTo(0, 10000)
                assertTrue(scroll.height > 0)
                assertTrue(action.height >= 56)
                assertTrue(action.width <= width)
                assertTrue(action.bottom <= scroll.scrollY + scroll.height)
                assertEquals(top, root.getChildAt(0).top)
            }
        } finally { controller.pause().stop().destroy() }
    }
}
