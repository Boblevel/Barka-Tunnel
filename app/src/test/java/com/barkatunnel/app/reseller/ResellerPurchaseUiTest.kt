package com.barkatunnel.app.reseller
import android.app.Application
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ResellerPurchaseUiTest {
    private fun texts(view:View):List<TextView> = (if(view is TextView)listOf(view) else emptyList()) +
        (if(view is ViewGroup)(0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    @Test fun pricesAndCopyUseActualPanelCredentials() {
        val controller=Robolectric.buildActivity(ResellerPurchaseActivity::class.java).create()
        val activity=controller.get()
        try {
            val views=texts(activity.findViewById(android.R.id.content))
            assertTrue(views.any { it.text.toString().contains("1 MOIS — 5 000 XOF") })
            assertTrue(views.any { it.text.toString().contains("2 MOIS — 10 000 XOF") })
            val account=JSONObject().put("panel_url","https://example.org/reseller").put("username","rev-example").put("password","sample-password").put("expires_at","2026-11-15")
            ResellerPurchaseActivity::class.java.getDeclaredField("account").apply { isAccessible=true }.set(activity,account)
            views.first { it.text.toString()=="TOUT COPIER" }.performClick()
            val copied=activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            for(value in listOf("https://example.org/reseller","rev-example","sample-password","2026-11-15"))assertTrue(copied.contains(value))
        } finally { controller.destroy() }
    }
}
