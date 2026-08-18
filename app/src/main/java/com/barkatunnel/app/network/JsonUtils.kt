package com.barkatunnel.app.network

import org.json.JSONObject

object JsonUtils {

    fun nullableString(
        json: JSONObject,
        key: String
    ): String? {
        return if (json.isNull(key)) null else json.optString(key, null)
    }

    fun nullableLong(
        json: JSONObject,
        key: String
    ): Long? {
        return if (json.isNull(key) || !json.has(key)) {
            null
        } else {
            json.optLong(key)
        }
    }
}
