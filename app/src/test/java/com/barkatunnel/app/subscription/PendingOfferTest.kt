package com.barkatunnel.app.subscription

import android.app.Application
import android.os.Looper
import android.widget.RadioGroup
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.backend.BackendPaymentStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value=BarkaBackendClient::class, isInAndroidSdk=false)
class PendingOfferBackend {
    @Implementation fun checkPaymentStatus(reference: String) = BackendPaymentStatus("pending", null, "En attente")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, shadows=[PendingOfferBackend::class])
class PendingOfferTest {
    @Test fun reopeningPurchaseRestoresItsOfferAndCannotReplaceItsReference() {
        val context=RuntimeEnvironment.getApplication()
        PendingPaymentStore.save(context,"BARKA-pending-offer","1m","https://example.org/checkout")
        val controller=Robolectric.buildActivity(SubscriptionActivity::class.java).setup()
        val activity=controller.get()
        try {
            val plans=activity.findViewById<RadioGroup>(R.id.plansGroup)
            assertEquals(R.id.plan1m,plans.checkedRadioButtonId)
            for(index in 0 until plans.childCount) assertFalse(plans.getChildAt(index).isEnabled)
            activity.findViewById<android.view.View>(R.id.payButton).performClick()
            assertEquals("https://example.org/checkout",shadowOf(activity).nextStartedActivity.data.toString())
            assertEquals("BARKA-pending-offer",PendingPaymentStore.reference(activity))
            val field=SubscriptionActivity::class.java.getDeclaredField("checkingPayment").apply {isAccessible=true}
            val end=System.nanoTime()+5_000_000_000L
            while(field.getBoolean(activity) && System.nanoTime()<end) {
                shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(field.getBoolean(activity))
        } finally {controller.pause().stop().destroy()}
    }
}
