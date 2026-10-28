package com.barkatunnel.app.ui.home

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.SystemBars
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

class SettingsActivity : Activity()

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fr-port-mdpi")
class Lot14UiTest {
    @Test fun titlesUseActualStatusBarAndRetainInsetAcrossRepeatedDispatch() {
        val controller=Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity=controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        activity.setContentView(R.layout.activity_settings)
        SystemBars.apply(activity)
        val content=activity.findViewById<ViewGroup>(android.R.id.content)
        val root=content.getChildAt(0) as ViewGroup
        val header=root.getChildAt(0) as ViewGroup
        for (top in listOf(24,48,0,32)) {
            val insets=WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0,top,0,24)).build()
            ViewCompat.dispatchApplyWindowInsets(content,insets)
            ViewCompat.dispatchApplyWindowInsets(content,insets)
            assertEquals(top,content.paddingTop)
            assertEquals(8,header.paddingTop)
            assertEquals(56,header.getChildAt(0).minimumHeight)
        }
        controller.pause().stop().destroy()
    }
    @Test fun spanishAndArabicHaveLocalizedTextAndCorrectDirection() {
        val app=RuntimeEnvironment.getApplication()
        for ((tag,expected,rtl) in listOf(Triple("es","AJUSTES",false),Triple("ar","الإعدادات",true))) {
            val config=Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag));setLayoutDirection(Locale.forLanguageTag(tag)) }
            val context=app.createConfigurationContext(config)
            assertEquals(expected,context.getString(R.string.settings_title))
            assertEquals(if(rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,context.resources.configuration.layoutDirection)
            assertFalse(context.getString(R.string.guide_9_text).startsWith("Préparer"))
            assertTrue(context.getString(R.string.tools_share_credentials,"barka","test").contains("barka"))
        }
    }
}
