package com.barkatunnel.app.ui.home

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 35], application = Application::class)
class FlatSettingsTest {
    @Test fun optionsAreFlatAndWhatsappFollowsTelegram() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        try {
            val root = activity.layoutInflater.inflate(R.layout.activity_settings, null)
            val telegram = root.findViewById<View>(R.id.telegramChannelButton)
            val whatsapp = root.findViewById<MaterialButton>(R.id.whatsappChannelButton)
            val parent = telegram.parent as ViewGroup
            assertEquals(parent.indexOfChild(telegram) + 1, parent.indexOfChild(whatsapp))
            assertNotNull(whatsapp.icon)
            fun checkButtons(view: View) {
                if (view is MaterialButton) {
                    assertNull(view.stateListAnimator)
                    assertEquals(0f, view.elevation)
                    view.isPressed = true
                    assertEquals(0f, view.translationZ)
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) checkButtons(view.getChildAt(index))
            }
            checkButtons(root)
            checkButtons(activity.layoutInflater.inflate(R.layout.item_legal_option, null))
        } finally { controller.pause().stop().destroy() }
    }
}
