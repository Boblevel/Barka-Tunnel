package com.barkatunnel.app.subscription

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PaymentConfirmationDialogTest {
    @Test fun confirmationHasOneTitleOfferAndOnlyTwoActions() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        var copied = false
        val dialog = PaymentConfirmationDialog.show(activity, "BARKA-TEST-CODE-1234", "1 SEMAINE • 800 XOF", { copied = true }, {})
        val decor = dialog.window!!.decorView
        fun texts(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        assertEquals(1, texts(decor).count { it == activity.getString(R.string.payment_confirmed) })
        assertEquals("1 SEMAINE • 800 XOF", decor.findViewById<TextView>(R.id.paymentConfirmedOffer).text.toString())
        assertFalse(texts(decor).contains(activity.getString(R.string.close)))
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility != View.VISIBLE)
        @Suppress("DEPRECATION")
        dialog.onBackPressed()
        assertTrue(dialog.isShowing)
        decor.findViewById<View>(R.id.paymentConfirmCopy).performClick()
        assertTrue(copied)
        assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }

    @Test fun failedActionKeepsDialogAndSuccessfulActivationClosesIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        var activated = false
        val dialog = PaymentConfirmationDialog.show(activity, "BARKA-TEST-CODE-1234", "24 HEURES", { error("clipboard failure") }, { activated = true })
        requireNotNull(dialog.findViewById<View>(R.id.paymentConfirmCopy)).performClick()
        assertTrue(dialog.isShowing)
        requireNotNull(dialog.findViewById<View>(R.id.paymentConfirmActivate)).performClick()
        assertTrue(activated)
        assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }

    @Test fun paidCodeAndPurchasedPlanRemainUntilAcknowledged() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        PendingPaymentStore.save(activity, "BT-REFERENCE", "1w")
        assertTrue(PendingPaymentStore.saveConfirmedCode(activity, "BT-REFERENCE", "BARKA-SAVED-CODE-1234"))
        assertFalse(PendingPaymentStore.save(activity, "BT-OTHER", "24h"))
        assertFalse(PendingPaymentStore.saveConfirmedCode(activity, "BT-OTHER", "BARKA-WRONG-CODE-1234"))
        assertEquals("1w", PendingPaymentStore.planId(activity.applicationContext))
        assertEquals("BARKA-SAVED-CODE-1234", PendingPaymentStore.confirmedCode(activity.applicationContext))
        assertEquals("BT-REFERENCE", PendingPaymentStore.reference(activity))
        PendingPaymentStore.clear(activity)
        assertNull(PendingPaymentStore.confirmedCode(activity))
        assertNull(PendingPaymentStore.reference(activity))
        controller.pause().stop().destroy()
    }
}
