package com.barkatunnel.app.ipfinder

import android.app.Activity
import android.app.Application
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.home.NonScrollingScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "fr-port-mdpi")
class IpFinderViewportTest {
    private fun boundsInRoot(view: View, root: View): RectF {
        val rect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var node = view
        while (node !== root) {
            node.matrix.mapRect(rect)
            rect.offset(node.left.toFloat(), node.top.toFloat())
            val parent = node.parent as View
            rect.offset(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
            node = parent
        }
        return rect
    }

    @Test fun allBlocksFitAndStopRemainsTappableOnSmallAndLargePhones() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        try {
            for ((width, height) in listOf(320 to 480, 360 to 560, 360 to 720, 411 to 850, 480 to 900)) {
                val root = activity.layoutInflater.inflate(R.layout.activity_ip_finder, null) as ViewGroup
                activity.setContentView(root)
                val viewport = root.getChildAt(1) as NonScrollingScrollView
                val stop = root.findViewById<View>(R.id.stopButton)
                assertEquals(View.INVISIBLE, stop.visibility)
                root.findViewById<TextView>(R.id.setAssistantButton).setText(R.string.ip_finder_assistant_selected)
                root.findViewById<TextView>(R.id.scanStatus).setText(R.string.ip_finder_searching)
                root.findViewById<TextView>(R.id.scanProgress).text = "Tentative 200"
                root.findViewById<TextView>(R.id.scanResult).text = "10.116.92.16"
                fun layout() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
                layout()
                val reserved = boundsInRoot(stop, root)
                stop.visibility = View.VISIBLE
                layout()
                assertEquals("Stop must not move the page", reserved, boundsInRoot(stop, root))
                for (id in listOf(R.id.backButton, R.id.ipInput, R.id.setAssistantButton,
                    R.id.wifiWarning, R.id.scanStatus, R.id.scanProgress, R.id.scanResult,
                    R.id.scanButton, R.id.stopButton)) {
                    val bounds = boundsInRoot(root.findViewById(id), root)
                    assertTrue("$id outside $width x $height: $bounds", bounds.left >= -1f &&
                        bounds.top >= -1f && bounds.right <= width + 1f && bounds.bottom <= height + 1f)
                }
                var clicked = false
                stop.setOnClickListener { clicked = true }
                val bounds = boundsInRoot(stop, root)
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(0L, 10L, action, bounds.centerX(), bounds.centerY(), 0)
                    root.dispatchTouchEvent(event)
                    event.recycle()
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Stop tap at $width x $height", clicked)
                viewport.scrollTo(0, 500)
                assertEquals(0, viewport.scrollY)
                val scroll = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_SCROLL, 0f, 0f, 0)
                assertFalse(viewport.onGenericMotionEvent(scroll))
                scroll.recycle()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
