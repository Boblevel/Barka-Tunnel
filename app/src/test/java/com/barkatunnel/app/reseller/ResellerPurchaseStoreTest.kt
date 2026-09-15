package com.barkatunnel.app.reseller
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ResellerPurchaseStoreTest {
    @Test fun pendingOrderAndOwnershipSurviveReopeningWithoutStoringPassword() {
        val context=RuntimeEnvironment.getApplication()
        val initial=ResellerPurchaseStore.read(context)
        assertEquals(64,initial.getString("owner_key").length)
        initial.put("request_id","pending-request").put("reference","BTR-receipt")
        ResellerPurchaseStore.write(context,initial)
        val restored=ResellerPurchaseStore.read(context)
        assertEquals(initial.toString(),restored.toString())
        assertFalse(restored.has("password"))
    }
}
