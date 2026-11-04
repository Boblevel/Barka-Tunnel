package com.barkatunnel.app.ui.home

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import com.barkatunnel.app.R
import com.barkatunnel.app.settings.LegalActivity
import com.barkatunnel.app.ui.BrandTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fr-port-mdpi")
class Lot18LegalThemeTest {
    @Test fun eachAccentIsAppliedToInflatedResourcesAndKeepsStatusColors() {
        val app = RuntimeEnvironment.getApplication()
        app.registerActivityLifecycleCallbacks(BrandTheme)
        try {
            for (key in listOf("orange", "red", "yellow", "purple")) {
                app.getSharedPreferences("barka_settings", Context.MODE_PRIVATE).edit().putString("theme", key).commit()
                val controller = Robolectric.buildActivity(LegalActivity::class.java).setup()
                val activity = controller.get()
                val value = TypedValue()
                assertTrue(activity.theme.resolveAttribute(R.attr.barkaAccent, value, true))
                assertEquals(BrandTheme.color(activity, R.color.barka_blue), value.data)
                assertEquals(activity.getColor(R.color.barka_green), BrandTheme.color(activity, R.color.barka_green))
                controller.pause().stop().destroy()
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(BrandTheme)
            app.getSharedPreferences("barka_settings", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Test fun legalMenuAndOfflinePoliciesDisplayContactAndRealSections() {
        for (policy in listOf(null, "privacy", "cookies", "terms")) {
            val intent = Intent(RuntimeEnvironment.getApplication(), LegalActivity::class.java)
            if (policy != null) intent.putExtra("policy", policy)
            val controller = Robolectric.buildActivity(LegalActivity::class.java, intent).setup()
            val activity = controller.get()
            val texts = mutableListOf<String>()
            fun collect(view: android.view.View) {
                if (view is TextView) texts.add(view.text.toString())
                if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            collect(activity.window.decorView)
            assertTrue(texts.any { it.contains("RHAFF SERVICE") })
            assertTrue(texts.any { it.contains("barkatunnel@gmail.com") })
            if (policy == null) assertTrue(texts.contains(activity.getString(R.string.legal_privacy)))
            else assertTrue(texts.size >= 8)
            controller.pause().stop().destroy()
        }
    }
}
