package com.barkatunnel.app.network

import java.net.HttpURLConnection
import java.net.URL

class HttpJsonClient {

    fun post(
        endpoint: String,
        json: String,
        token: String? = null
    ): HttpResponse {

        val url = URL(ApiConfig.BASE_URL + endpoint)

        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            if (!token.isNullOrBlank()) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
            }

            connection.outputStream.use {
                it.write(json.toByteArray())
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""

            HttpResponse(
                code = code,
                body = body
            )

        } finally {
            connection.disconnect()
        }
    }
}
