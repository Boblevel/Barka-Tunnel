package com.barkatunnel.app.pricing

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class Promotion(val amount: Int, val startsAt: Long, val endsAt: Long)
data class PriceCatalog(val revision: Long, val prices: Map<String, Int>,
    val promotions: Map<String, Promotion> = emptyMap(), val serverTime: Long = 0)

object PricingStore {
    private const val PREFS = "barka_price_catalog"
    private const val KEY = "catalog"
    val defaults = mapOf("24h" to 300, "1w" to 800, "2w" to 1000, "1m" to 2000, "3m" to 6000,
        "reseller_1m" to 5000, "reseller_2m" to 10000)
    private fun boot(context: Context): Int = if (android.os.Build.VERSION.SDK_INT >= 24)
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) else -1
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun parse(body: JSONObject, now: Long = body.optLong("server_time", System.currentTimeMillis() / 1000)): PriceCatalog {
        require(body.getString("currency") == "XOF")
        val revision = body.get("revision")
        require((revision is Int || revision is Long) && (revision as Number).toLong() >= 0)
        val values = body.optJSONObject("base_prices") ?: body.getJSONObject("prices")
        val keys = values.keys().asSequence().toSet()
        require(keys == defaults.keys || keys == defaults.keys - "3m")
        val prices = defaults.mapValues { (key, _) ->
            val amount = if (key == "3m" && !values.has(key)) defaults.getValue(key) else values.get(key)
            require((amount is Int || amount is Long) && (amount as Number).toLong() in 1..10_000_000L)
            (amount as Number).toInt()
        }
        val promos = body.optJSONObject("promotions") ?: JSONObject()
        val promotions = promos.keys().asSequence().associateWith { key ->
            require(key in defaults)
            val item = promos.getJSONObject(key)
            Promotion(item.getInt("amount"), item.getLong("starts_at"), item.getLong("ends_at")).also {
                require(it.amount in 1 until prices.getValue(key) && it.startsAt >= 0 && it.endsAt > it.startsAt)
            }
        }
        val effective = prices.toMutableMap()
        promotions.forEach { (key, promotion) -> if (now >= promotion.startsAt && now < promotion.endsAt) effective[key] = promotion.amount }
        return PriceCatalog((revision as Number).toLong(), effective, promotions, now)
    }

    fun read(context: Context): PriceCatalog = runCatching {
        val p = prefs(context)
        val body = JSONObject(p.getString(KEY, null) ?: error("No catalog"))
        val elapsed = android.os.SystemClock.elapsedRealtime() - p.getLong("received_elapsed", 0)
        val wallDelta = System.currentTimeMillis() - p.getLong("received_wall", System.currentTimeMillis())
        val seconds = (if (elapsed >= 0 && p.getInt("received_boot", -1) == boot(context)) elapsed else wallDelta).coerceAtLeast(0) / 1000
        parse(body, body.optLong("server_time", System.currentTimeMillis() / 1000) + seconds)
    }.getOrDefault(PriceCatalog(0, defaults))

    @Synchronized fun save(context: Context, body: JSONObject) {
        val incoming = parse(body)
        val current = read(context)
        if (incoming.revision < current.revision) return
        check(prefs(context).edit().putString(KEY, body.toString()).putLong("received_elapsed", android.os.SystemClock.elapsedRealtime())
            .putLong("received_wall", System.currentTimeMillis()).putInt("received_boot", boot(context)).commit())
    }

    fun listen(context: Context, changed: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY) changed() }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }
    fun unlisten(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
