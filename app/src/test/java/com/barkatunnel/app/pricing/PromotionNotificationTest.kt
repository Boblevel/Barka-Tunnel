package com.barkatunnel.app.pricing

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PromotionNotificationTest {
    @Test fun alertStartsOnceAndCancelsAtExpiry() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("barka_price_catalog", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("barka_promotion_alerts", Context.MODE_PRIVATE).edit().clear().commit()
        val body = JSONObject().put("revision",1).put("currency","XOF").put("server_time",100L)
            .put("prices",JSONObject(PricingStore.defaults))
            .put("promotions",JSONObject().put("24h",JSONObject().put("amount",150).put("starts_at",110).put("ends_at",120)))
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(manager)
        PricingStore.save(app,body)
        PromotionNotifications.publish(app)
        assertNull(notifications.getNotification(2300))
        PricingStore.save(app,body.put("server_time",110L))
        PromotionNotifications.publish(app)
        assertNotNull(notifications.getNotification(2300))
        manager.cancel(2300)
        PricingStore.save(app,body.put("revision",2))
        PromotionNotifications.publish(app)
        assertNull(notifications.getNotification(2300))
        PricingStore.save(app,body.put("revision",3).put("server_time",120L))
        PromotionNotifications.publish(app)
        assertNull(notifications.getNotification(2300))
        body.getJSONObject("promotions").getJSONObject("24h").put("starts_at",120).put("ends_at",150)
        PricingStore.save(app,body.put("revision",4))
        PromotionNotifications.publish(app)
        assertNotNull(notifications.getNotification(2300))
        PricingStore.save(app,body.put("server_time",150))
        PromotionNotifications.publish(app)
        assertNull(notifications.getNotification(2300))
    }
}
