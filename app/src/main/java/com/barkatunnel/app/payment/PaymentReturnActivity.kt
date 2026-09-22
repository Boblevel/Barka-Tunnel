package com.barkatunnel.app.payment

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.reseller.ResellerPurchaseActivity
import com.barkatunnel.app.reseller.ResellerPurchaseStore
import com.barkatunnel.app.subscription.PendingPaymentStore
import com.barkatunnel.app.subscription.SubscriptionActivity

/** The link only navigates; the authenticated status endpoint verifies the purchase. */
class PaymentReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val reference = if (uri?.isHierarchical == true && uri.scheme == "barkatunnel" && uri.host == "payment-return")
            uri.getQueryParameter("reference")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,100}")) } else null
        val target = when {
            reference != null && reference == PendingPaymentStore.reference(this) -> SubscriptionActivity::class.java
            reference != null && reference == runCatching {
                ResellerPurchaseStore.read(this).optString("reference")
            }.getOrNull() -> ResellerPurchaseActivity::class.java
            else -> MainActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("payment_return", true))
        finish()
    }
}
