package com.barkatunnel.app.pricing

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fr")
class PromotionTest {
    private fun body()=JSONObject().put("revision",1).put("currency","XOF").put("server_time",100L)
        .put("prices",JSONObject(PricingStore.defaults)).put("base_prices",JSONObject(PricingStore.defaults))
        .put("promotions",JSONObject().put("24h",JSONObject().put("amount",150).put("starts_at",110).put("ends_at",120)))
    @Test fun boundariesReturnNormalPriceWithoutServerRefresh() {
        assertEquals(300,PricingStore.parse(body(),109).prices["24h"])
        assertEquals(150,PricingStore.parse(body(),110).prices["24h"])
        assertEquals(150,PricingStore.parse(body(),119).prices["24h"])
        assertEquals(300,PricingStore.parse(body(),120).prices["24h"])
    }
    @Test fun countdownUsesServerTimeAndHidesForDifferentQuotedPrice() {
        val c=RuntimeEnvironment.getApplication()
        PricingStore.save(c,body().put("server_time",115L))
        assertTrue(PricingLabels.offer(c,"24h",150).contains("00:00:05"))
        assertFalse(PricingLabels.offer(c,"24h",300).contains("PROMOTION"))
    }
}
