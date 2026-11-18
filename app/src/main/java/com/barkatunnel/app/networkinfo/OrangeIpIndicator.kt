package com.barkatunnel.app.networkinfo

import android.content.Context

/** Display-only preference; never changes IP Finder criteria or VPN profiles. */
object OrangeIpIndicator {
    private const val PREFS = "orange_ip_indicator"

    fun synchronize(context: Context, value: Any?) {
        // Older backends omit this field. A missing/invalid value keeps the last setting.
        if (value !is Boolean) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("enabled", true) != value) prefs.edit().putBoolean("enabled", value).apply()
    }

    fun enabledFor(context: Context, networkId: String?): Boolean =
        networkId !in listOf("orange_bf", "orange_bf_v2") ||
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)
}
