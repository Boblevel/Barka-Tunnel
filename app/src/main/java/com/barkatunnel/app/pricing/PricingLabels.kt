package com.barkatunnel.app.pricing

import android.content.Context
import androidx.core.os.ConfigurationCompat
import com.barkatunnel.app.R
import java.text.NumberFormat

object PricingLabels {
    fun offer(context: Context, key: String, amount: Int): CharSequence {
        val label = when (key) {
            "24h" -> R.string.price_offer_day
            "1w" -> R.string.price_offer_week
            "2w" -> R.string.price_offer_two_weeks
            "1m" -> R.string.price_offer_month
            "3m" -> R.string.price_offer_three_months
            "reseller_1m" -> R.string.price_reseller_month
            "reseller_2m" -> R.string.price_reseller_two_months
            else -> error("Unknown offer")
        }
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0]
            ?: java.util.Locale.getDefault()
        val text = context.getString(label, NumberFormat.getIntegerInstance(locale).format(amount))
        val catalog = PricingStore.read(context)
        val promo = catalog.promotions[key] ?: return text
        if (catalog.serverTime < promo.startsAt || catalog.serverTime >= promo.endsAt || amount != promo.amount) return text
        val seconds = promo.endsAt - catalog.serverTime
        val remaining = String.format(locale, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
        return android.text.SpannableString(text + "\n" + context.getString(R.string.promotion_remaining, remaining)).apply {
            setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.rgb(21, 128, 61)),
                0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
