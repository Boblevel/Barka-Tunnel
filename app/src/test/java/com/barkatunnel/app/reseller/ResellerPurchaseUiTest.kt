package com.barkatunnel.app.reseller
import android.app.Application
import com.barkatunnel.app.R
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
@Config(sdk=[28],application=Application::class,qualifiers="fr")
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
            assertEquals(View.GONE,activity.findViewById<View>(R.id.resellerCredentialsCard).visibility)
            val render=ResellerPurchaseActivity::class.java.getDeclaredMethod("render",JSONObject::class.java).apply { isAccessible=true }
            render.invoke(activity,JSONObject().put("status","pending").put("message","Paiement en attente de confirmation."))
            assertEquals(View.GONE,activity.findViewById<View>(R.id.resellerCredentialsCard).visibility)
            render.invoke(activity,JSONObject().put("status","paid").put("message","Paiement confirmé.").put("account",account))
            assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.resellerCredentialsCard).visibility)
            assertEquals("rev-example",activity.findViewById<TextView>(R.id.resellerUsername).text.toString())
            assertFalse(views.any { it.text.toString().contains("sous-panel",ignoreCase=true) })
            views.first { it.text.toString()=="TOUT COPIER" }.performClick()
            val copied=activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            for(value in listOf("https://example.org/reseller","rev-example","sample-password","2026-11-15"))assertTrue(copied.contains(value))
        } finally { controller.destroy() }
    }
    @Test @Config(qualifiers="en") fun resellerPageUsesEnglishAndKeepsCardPresentation() {
        val controller=Robolectric.buildActivity(ResellerPurchaseActivity::class.java).create()
        val activity=controller.get()
        try {
            val views=texts(activity.findViewById(android.R.id.content))
            assertTrue(views.any { it.text.toString()=="Become a Reseller" })
            assertTrue(views.any { it.text.toString().contains("1 MONTH — 5,000 XOF") })
            assertTrue(views.any { it.text.toString().contains("2 MONTHS — 10,000 XOF") })
            assertNotNull(activity.findViewById<View>(R.id.resellerMonth).background)
            val render=ResellerPurchaseActivity::class.java.getDeclaredMethod("render",JSONObject::class.java).apply { isAccessible=true }
            render.invoke(activity,JSONObject().put("status","pending").put("message","Paiement en attente de confirmation."))
            assertEquals("Waiting for payment confirmation.",activity.findViewById<TextView>(R.id.resellerStatus).text.toString())
            val root=activity.findViewById<View>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY))
            root.layout(0,0,360,800)
            assertTrue(activity.findViewById<View>(R.id.resellerPay).height>=48)
        } finally { controller.destroy() }
    }

}
