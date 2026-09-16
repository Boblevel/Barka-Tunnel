package com.barkatunnel.app.reseller

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.backend.BarkaBackendException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.Collections

@Implements(value=BarkaBackendClient::class, isInAndroidSdk=false)
class CheckoutBackendShadow {
    companion object {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var result = "failed"
    }
    @Implementation fun resellerPurchase(action: String, payload: JSONObject): JSONObject {
        calls.add(action)
        return when(action) {
            "account" -> JSONObject()
            "start" -> JSONObject().put("payment_reference", "BTR-return-test").put("status", "pending")
                .put("checkout_url", "https://example.org/checkout").put("amount", 5000 * payload.getInt("months"))
            "cancel" -> {
                if (result == "offline") throw BarkaBackendException("Connectez-vous à un réseau, puis réessayez.")
                JSONObject().put("status", result).put("message", if(result=="paid") "Paiement confirmé." else "Paiement annulé ou expiré.")
                    .also { if(result=="paid") it.put("account",JSONObject().put("username","rev-paid").put("password","paid-secret").put("panel_url","https://example.org/reseller").put("expires_at","2026-12-01")) }
            }
            else -> throw AssertionError("Unexpected action: $action")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="fr", shadows=[CheckoutBackendShadow::class])
class ResellerCheckoutReturnTest {
    @Before fun reset() { CheckoutBackendShadow.calls.clear(); CheckoutBackendShadow.result="failed" }
    private fun idle(activity: ResellerPurchaseActivity) {
        val busy=ResellerPurchaseActivity::class.java.getDeclaredField("busy").apply { isAccessible=true }
        val end=System.nanoTime()+5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if(!busy.getBoolean(activity)) return
            Thread.sleep(10)
        } while(System.nanoTime()<end)
        fail("Backend callback timed out")
    }
    private fun texts(view: View): List<String> = (if(view is TextView) listOf(view.text.toString()) else emptyList()) +
        (if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())

    @Test fun unpaidReturnCancelsAndUnlocksANewPurchase() {
        val controller=Robolectric.buildActivity(ResellerPurchaseActivity::class.java).create().start().resume()
        val a=controller.get()
        try {
            idle(a)
            val before=ResellerPurchaseStore.read(a).getString("owner_key")
            val labels=texts(a.findViewById(android.R.id.content))
            assertFalse(labels.contains("VÉRIFIER LE PAIEMENT"))
            assertFalse(labels.contains("J’AI DÉJÀ UN COMPTE REVENDEUR"))
            a.findViewById<View>(R.id.resellerPay).performClick();idle(a)
            assertNotNull(shadowOf(a).nextStartedActivity)
            assertFalse(a.findViewById<View>(R.id.resellerTwoMonths).isEnabled)
            controller.pause().resume();idle(a)
            assertEquals(listOf("account","start","cancel"),CheckoutBackendShadow.calls.toList())
            val state=ResellerPurchaseStore.read(a)
            assertEquals(before,state.getString("owner_key"))
            assertFalse(state.has("reference"));assertFalse(state.has("request_id"))
            assertEquals("PAYER",a.findViewById<TextView>(R.id.resellerPay).text.toString())
            assertTrue(a.findViewById<View>(R.id.resellerTwoMonths).isEnabled)
            assertEquals(View.GONE,a.findViewById<View>(R.id.resellerCredentialsCard).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun paidReturnKeepsCredentials() {
        CheckoutBackendShadow.result="paid"
        val c=Robolectric.buildActivity(ResellerPurchaseActivity::class.java).create().start().resume();val a=c.get()
        try {
            idle(a);a.findViewById<View>(R.id.resellerPay).performClick();idle(a)
            c.pause().resume();idle(a)
            assertEquals(View.VISIBLE,a.findViewById<View>(R.id.resellerCredentialsCard).visibility)
            assertEquals("rev-paid",a.findViewById<TextView>(R.id.resellerUsername).text.toString())
            assertFalse(ResellerPurchaseStore.read(a).has("reference"))
        } finally { c.pause().stop().destroy() }
    }

    @Test fun offlineReturnRetainsReceiptUntilConfirmedCancellation() {
        CheckoutBackendShadow.result="offline"
        val c=Robolectric.buildActivity(ResellerPurchaseActivity::class.java).create().start().resume();val a=c.get()
        try {
            idle(a);a.findViewById<View>(R.id.resellerPay).performClick();idle(a)
            c.pause().resume();idle(a)
            assertEquals("BTR-return-test",ResellerPurchaseStore.read(a).getString("reference"))
            assertFalse(a.findViewById<View>(R.id.resellerPay).isEnabled)
            CheckoutBackendShadow.result="failed"
            c.pause().resume();idle(a)
            assertFalse(ResellerPurchaseStore.read(a).has("reference"))
        } finally { c.pause().stop().destroy() }
    }
}
