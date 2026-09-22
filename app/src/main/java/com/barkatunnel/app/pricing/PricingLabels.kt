package com.barkatunnel.app.pricing

import android.content.Context
import androidx.core.os.ConfigurationCompat
import com.barkatunnel.app.R
import java.text.NumberFormat

object PricingLabels {
    fun offer(context: Context, key: String, amount: Int): String {
        val label = when (key) {
            "24h" -> R.string.price_offer_day
            "1w" -> R.string.price_offer_week
            "2w" -> R.string.price_offer_two_weeks
            "1m" -> R.string.price_offer_month
            "reseller_1m" -> R.string.price_reseller_month
            "reseller_2m" -> R.string.price_reseller_two_months
            else -> error("Unknown offer")
        }
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0]
            ?: java.util.Locale.getDefault()
        return context.getString(label, NumberFormat.getIntegerInstance(locale).format(amount))
    }
}
