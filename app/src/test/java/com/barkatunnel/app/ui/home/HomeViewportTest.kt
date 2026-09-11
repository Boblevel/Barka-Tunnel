package com.barkatunnel.app.ui.home

import android.app.Application
import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class HomeViewportTest {
    @Test fun fitsWidthAndHeightWithoutDistortingOrLosingButtonTaps() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = controller.get()
        for (width in listOf(320, 360, 411, 480)) {
            for (height in listOf(420, 560, 650, 800)) {
                val viewport = NonScrollingScrollView(context)
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(18, 14, 18, 20)
                }
                viewport.addView(content)
                content.addView(View(context), LinearLayout.LayoutParams(-1, 110))
                content.addView(TextView(context).apply {
                    text = "RÉSEAU SÉLECTIONNÉ • MOOV-AFRICA BF"
                    textSize = 17f
                }, LinearLayout.LayoutParams(-1, -2))
                content.addView(View(context), LinearLayout.LayoutParams(-1, 110))
                val circle = View(context)
                content.addView(circle, LinearLayout.LayoutParams(220, 220))
                content.addView(TextView(context).apply {
                    text = "NON CONNECTÉ\nTemps de connexion : 00:00:00"
                    textSize = 18f
                }, LinearLayout.LayoutParams(-1, -2))
                val button = TextView(context).apply {
                    text = "AJOUTER"
                    isClickable = true
                }
                content.addView(button, LinearLayout.LayoutParams(116, 48))
                var clicked = false
                button.setOnClickListener { clicked = true }
                context.setContentView(viewport)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                viewport.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                viewport.layout(0, 0, width, height)
                val scale = content.scaleX
                assertEquals("uniform scaling", scale, content.scaleY, 0f)
                assertEquals("filled width $width/$height", width.toFloat(), content.width * scale, 2f)
                assertTrue("fits height", content.height * scale <= height + 1f)
                assertEquals(circle.width * scale, circle.height * scale, 0f)
                val x = (button.left + button.width / 2f) * scale + content.left
                val y = (button.top + button.height / 2f) * scale + content.top
                assertTrue(x in 0f..width.toFloat() && y in 0f..height.toFloat())
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(0L, 10L, action, x, y, 0)
                    viewport.dispatchTouchEvent(event)
                    event.recycle()
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertTrue("scaled button remains clickable $width/$height", clicked)
                viewport.scrollTo(100, 100)
                assertEquals(0, viewport.scrollX)
                assertEquals(0, viewport.scrollY)
            }
        }
        controller.pause().stop().destroy()
    }
}
