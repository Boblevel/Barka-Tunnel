package com.barkatunnel.app.pricing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.subscription.SubscriptionActivity
import com.barkatunnel.app.reseller.ResellerPurchaseActivity

/** One alert per offer and promotion period, independent of catalog revisions. */
object PromotionNotifications {
    private const val CHANNEL = "barka_promotions"

    @Synchronized fun publish(context: Context) {
        val catalog = PricingStore.read(context)
        val prefs = context.getSharedPreferences("barka_promotion_alerts", Context.MODE_PRIVATE)
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val system = context.getSystemService(NotificationManager::class.java)
            system.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.promotion_alert_title), NotificationManager.IMPORTANCE_DEFAULT))
            if (system.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        }
        PricingStore.defaults.keys.forEachIndexed { index, key ->
            val id = 2300 + index
            val promo = catalog.promotions[key]
            if (promo == null || catalog.serverTime !in promo.startsAt until promo.endsAt) {
                manager.cancel(id)
                return@forEachIndexed
            }
            val identity = "${promo.startsAt}:${promo.endsAt}:${promo.amount}"
            if (prefs.getString(key, null) == identity) return@forEachIndexed
            val reseller = key.startsWith("reseller_")
            val intent = Intent(context, if (reseller) ResellerPurchaseActivity::class.java else SubscriptionActivity::class.java)
            val destination = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val message = context.getString(if (reseller) R.string.promotion_alert_reseller else R.string.promotion_alert_subscription,
                promo.amount)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_barka)
                .setContentTitle(context.getString(R.string.promotion_alert_title))
                .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(destination).setAutoCancel(true)
                .setTimeoutAfter((promo.endsAt - catalog.serverTime) * 1000)
                .setColor(ContextCompat.getColor(context, R.color.barka_blue)).build()
            try {
                manager.notify(id, notification)
                prefs.edit().putString(key, identity).commit()
            } catch (_: SecurityException) { /* Permission can be revoked during delivery. */ }
        }
    }
}
