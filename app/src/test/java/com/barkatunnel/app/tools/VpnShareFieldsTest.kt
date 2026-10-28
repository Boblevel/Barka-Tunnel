package com.barkatunnel.app.tools

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class VpnShareFieldsTest {
    @Test fun credentialsAreSeparatedAndClearedWhenSharingStops() {
        val field=VpnShareService::class.java.getDeclaredField("state").apply {isAccessible=true}
        field.set(null,VpnShareService.State("192.168.43.1",36399,"test-password"))
        val controller=Robolectric.buildActivity(VpnToolsActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(),VpnToolsActivity::class.java).putExtra("share",true)).setup()
        try {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val activity=controller.get()
            assertEquals(View.GONE,activity.findViewById<View>(R.id.toolValue).visibility)
            for ((id,value) in listOf(R.id.shareAddress to "192.168.43.1",R.id.sharePort to "36399",
                R.id.shareUsername to "barka",R.id.sharePassword to "test-password")) {
                val view=activity.findViewById<TextView>(id)
                assertEquals(value,view.text.toString());assertTrue(view.isTextSelectable)
            }
            val root=activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup
            for ((width,height) in listOf(320 to 480,390 to 800,800 to 320)) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                root.layout(0,0,width,height)
                val scroll=root.getChildAt(1) as android.widget.ScrollView
                scroll.scrollTo(0,10000)
                val button=root.findViewById<View>(R.id.toolAction)
                assertTrue(button.height>=56)
                assertTrue(button.bottom<=scroll.scrollY+scroll.height)
            }
            field.set(null,null)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertEquals(View.GONE,activity.findViewById<View>(R.id.shareDetails).visibility)
            assertEquals("",activity.findViewById<TextView>(R.id.sharePassword).text.toString())
        } finally {field.set(null,null);controller.pause().stop().destroy()}
    }
}
