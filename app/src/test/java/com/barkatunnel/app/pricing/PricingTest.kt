package com.barkatunnel.app.pricing

import android.app.Application
import android.os.Looper
import android.widget.TextView
import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.backend.BackendPaymentStatus
import com.barkatunnel.app.reseller.ResellerPurchaseActivity
import com.barkatunnel.app.reseller.ResellerPurchaseStore
import com.barkatunnel.app.subscription.SubscriptionActivity
import com.barkatunnel.app.subscription.PendingPaymentStore
import com.barkatunnel.app.journal.JournalActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.*

@Implements(value=BarkaBackendClient::class, isInAndroidSdk=false)
class PricingBackendShadow {
    @Implementation fun checkPaymentStatus(reference: String) = BackendPaymentStatus("pending",null,"")
    @Implementation fun resellerPurchase(action: String, payload: JSONObject): JSONObject =
        if(action=="account") JSONObject() else JSONObject().put("status","pending")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="fr", shadows=[PricingBackendShadow::class])
class PricingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun catalog(revision: Int, day: Int=450, reseller: Int=6500) = JSONObject()
        .put("revision",revision).put("currency","XOF")
        .put("prices",JSONObject(PricingStore.defaults).put("24h",day).put("reseller_1m",reseller))
    private fun settle(activity: Any, fieldName: String) {
        val field=activity.javaClass.getDeclaredField(fieldName).apply {isAccessible=true}
        val end=System.nanoTime()+5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if(!field.getBoolean(activity))return
            Thread.sleep(10)
        }while(System.nanoTime()<end)
        fail("Callback did not complete")
    }
    @Test fun cachedCatalogRejectsInvalidAndOlderResponses() {
        PricingStore.save(context,catalog(3))
        for(invalid in listOf(catalog(4).put("currency","EUR"),catalog(4).also {it.getJSONObject("prices").put("24h",0)},
            catalog(4).also {it.getJSONObject("prices").remove("1w")},catalog(4).also {it.getJSONObject("prices").put("24h",1.5)})) {
            assertTrue(runCatching {PricingStore.save(context,invalid)}.isFailure)
            assertEquals(450,PricingStore.read(context).prices["24h"])
        }
        PricingStore.save(context,catalog(2,999))
        assertEquals(3L,PricingStore.read(context).revision)
        assertEquals(450,PricingStore.read(context).prices["24h"])
    }
    @Test fun subscriptionPricesRefreshOnScreenWithoutToast() {
        PricingStore.save(context,catalog(1))
        val c=Robolectric.buildActivity(SubscriptionActivity::class.java).setup();val a=c.get()
        try {
            assertTrue(a.findViewById<TextView>(R.id.plan24h).text.contains("450"))
            ShadowToast.reset()
            PricingStore.save(context,catalog(2,700));shadowOf(Looper.getMainLooper()).idle()
            assertTrue(a.findViewById<TextView>(R.id.plan24h).text.contains("700"))
            assertEquals(0,ShadowToast.shownToastCount())
        } finally {c.pause().stop().destroy()}
    }
    @Test fun pendingSubscriptionKeepsItsOriginalPriceAfterCatalogChange() {
        PendingPaymentStore.save(context,"BT-pending-price","24h","https://example.org/checkout",300)
        PricingStore.save(context,catalog(1,700))
        val c=Robolectric.buildActivity(SubscriptionActivity::class.java).setup();val a=c.get()
        try {
            settle(a,"checkingPayment")
            assertTrue(a.findViewById<TextView>(R.id.plan24h).text.contains("300"))
            PricingStore.save(context,catalog(2,900));shadowOf(Looper.getMainLooper()).idle()
            assertTrue(a.findViewById<TextView>(R.id.plan24h).text.contains("300"))
            assertEquals(300,PendingPaymentStore.amount(a))
        } finally {c.pause().stop().destroy()}
    }
    @Test fun resellerLabelsRefreshWithoutChangingSelectedOffer() {
        PricingStore.save(context,catalog(1))
        val c=Robolectric.buildActivity(ResellerPurchaseActivity::class.java).setup();val a=c.get()
        try {
            settle(a,"busy")
            val before=a.findViewById<android.widget.RadioGroup>(R.id.resellerPlans).checkedRadioButtonId
            PricingStore.save(context,catalog(2,reseller=7200));shadowOf(Looper.getMainLooper()).idle()
            assertTrue(a.findViewById<TextView>(R.id.resellerMonth).text.toString().replace(Regex("\\s|\\u00a0|\\u202f"),"").contains("7200"))
            assertEquals(before,a.findViewById<android.widget.RadioGroup>(R.id.resellerPlans).checkedRadioButtonId)
        } finally {c.pause().stop().destroy()}
    }
    @Test fun pendingResellerKeepsQuotedPrice() {
        val state=ResellerPurchaseStore.read(context).put("reference","BTR-pending-price").put("months",1).put("amount",5000)
        ResellerPurchaseStore.write(context,state)
        PricingStore.save(context,catalog(1,reseller=7200))
        val c=Robolectric.buildActivity(ResellerPurchaseActivity::class.java).setup();val a=c.get()
        try {
            settle(a,"busy")
            assertTrue(a.findViewById<TextView>(R.id.resellerMonth).text.toString().replace(Regex("\\s|\\u00a0|\\u202f"),"").contains("5000"))
        } finally {c.pause().stop().destroy()}
    }
    @Test @Config(qualifiers="en") fun labelsUseEnglishAndCurrentAmount() {
        assertEquals("24 HOURS — 450 XOF",PricingLabels.offer(context,"24h",450))
        assertTrue(PricingLabels.offer(context,"reseller_1m",5000).contains("Renewable"))
    }
    @Test fun journalStillScrollsWithoutScrollbarOrEdgeEffect() {
        fun find(view: View): ScrollView? {
            if(view is ScrollView)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        val c=Robolectric.buildActivity(JournalActivity::class.java).setup()
        try {
            val scroll=find(c.get().findViewById(android.R.id.content))!!
            assertFalse(scroll.isVerticalScrollBarEnabled)
            assertEquals(View.OVER_SCROLL_NEVER,scroll.overScrollMode)
            assertTrue(scroll.isFillViewport)
        }finally{c.pause().stop().destroy()}
    }
}
