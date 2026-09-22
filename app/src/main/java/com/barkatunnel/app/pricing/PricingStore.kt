package com.barkatunnel.app.pricing

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class PriceCatalog(val revision: Long, val prices: Map<String, Int>)

object PricingStore {
    private const val PREFS = "barka_price_catalog"
    private const val KEY = "catalog"
    val defaults = mapOf("24h" to 300, "1w" to 800, "2w" to 1000, "1m" to 2000,
        "reseller_1m" to 5000, "reseller_2m" to 10000)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun parse(body: JSONObject): PriceCatalog {
        require(body.getString("currency") == "XOF")
        val revision = body.get("revision")
        require((revision is Int || revision is Long) && (revision as Number).toLong() >= 0)
        val values = body.getJSONObject("prices")
        require(values.keys().asSequence().toSet() == defaults.keys)
        val prices = defaults.mapValues { (key, _) ->
            val amount = values.get(key)
            require((amount is Int || amount is Long) && (amount as Number).toLong() in 1..10_000_000L)
            (amount as Number).toInt()
        }
        return PriceCatalog((revision as Number).toLong(), prices)
    }

    fun read(context: Context): PriceCatalog = runCatching {
        parse(JSONObject(prefs(context).getString(KEY, null) ?: error("No catalog")))
    }.getOrDefault(PriceCatalog(0, defaults))

    @Synchronized fun save(context: Context, body: JSONObject) {
        val incoming = parse(body)
        val current = read(context)
        if (incoming.revision < current.revision) return
        if (incoming == current && prefs(context).contains(KEY)) return
        check(prefs(context).edit().putString(KEY, body.toString()).commit())
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
