package com.barkatunnel.app.guide

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class DiscoveryActivity : FragmentActivity() {
    override fun onCreate(state: Bundle?) {
        setTheme(R.style.Theme_BarkaTunnel)
        super.onCreate(state)
        setContentView(R.layout.activity_guide)
        VideoGuide.bind(this, findViewById(R.id.videoGuideButton), "guide")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VideoDiscoveryTest {
    @Test fun reminderReturnsUntilOpenedAndRemainsIndependentPerScreen() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("barka_video_discovery", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val controller = Robolectric.buildActivity(DiscoveryActivity::class.java).setup()
        val hint = controller.get().findViewById<View>(R.id.videoGuideHint)
        assertEquals(View.VISIBLE, hint.visibility)
        assertNotNull(hint.animation)
        controller.pause()
        assertNull(hint.animation)
        controller.resume()
        assertNotNull(hint.animation)
        prefs.edit().putBoolean("opened_ip_finder", true).commit()
        controller.pause().resume()
        assertEquals(View.VISIBLE, hint.visibility)
        prefs.edit().putBoolean("opened_guide", true).commit()
        controller.pause().resume()
        assertEquals(View.GONE, hint.visibility)
        assertNull(hint.animation)
        controller.pause().stop().destroy()
        val recreated = Robolectric.buildActivity(DiscoveryActivity::class.java).setup()
        assertEquals(View.GONE, recreated.get().findViewById<View>(R.id.videoGuideHint).visibility)
        recreated.pause().stop().destroy()
        prefs.edit().clear().commit()
    }
}
