package com.barkatunnel.app.payment

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.reseller.ResellerPurchaseActivity
import com.barkatunnel.app.reseller.ResellerPurchaseStore
import com.barkatunnel.app.subscription.PendingPaymentStore
import com.barkatunnel.app.subscription.SubscriptionActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PaymentReturnTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun route(reference: String): Intent {
        val controller = Robolectric.buildActivity(PaymentReturnActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse("barkatunnel://payment-return?reference=$reference"))).create()
        return shadowOf(controller.get()).nextStartedActivity.also { controller.destroy() }
    }
    @Test fun ownedSubscriptionReturnsToReceiptWithoutTrustingTheLinkAsPayment() {
        PendingPaymentStore.save(context, "BARKA-owned-ref", "24h")
        assertEquals(SubscriptionActivity::class.java.name, route("BARKA-owned-ref").component?.className)
        assertNull(PendingPaymentStore.confirmedCode(context))
    }
    @Test fun ownedResellerReturnsToItsAccountAndPreservesOwnerKey() {
        val state = ResellerPurchaseStore.read(context).put("reference", "BTR-owned-ref")
        ResellerPurchaseStore.write(context, state)
        val result = route("BTR-owned-ref")
        assertEquals(ResellerPurchaseActivity::class.java.name, result.component?.className)
        assertTrue(result.getBooleanExtra("payment_return", false))
        assertEquals(state.getString("owner_key"), ResellerPurchaseStore.read(context).getString("owner_key"))
    }
    @Test fun foreignReferenceCannotReplaceTheStoredPurchase() {
        PendingPaymentStore.save(context, "BARKA-owned-ref", "24h")
        assertEquals(MainActivity::class.java.name, route("BARKA-foreign-ref").component?.className)
        assertEquals("BARKA-owned-ref", PendingPaymentStore.reference(context))
    }
}
