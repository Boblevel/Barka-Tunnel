package com.barkatunnel.app.backend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Explicit API requests through the active tunnel; no global proxy or automatic retry. */
internal object TunnelControlTransport {
    private val clients = ConcurrentHashMap<Int, OkHttpClient>()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Drop pooled sockets and pending requests belonging to the previous native session. */
    @Synchronized
    fun closeSession(socksPort: Int) {
        clients.remove(socksPort)?.let { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    @Synchronized
    private fun clientFor(socksPort: Int): OkHttpClient = clients.getOrPut(socksPort) {
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun request(url: String, payload: JSONObject?, socksPort: Int): JSONObject =
        JSONObject(requestText(url, payload, socksPort))

    fun requestArray(url: String, socksPort: Int): org.json.JSONArray =
        org.json.JSONArray(requestText(url, null, socksPort))

    private fun requestText(url: String, payload: JSONObject?, socksPort: Int): String {
        require(socksPort in 1..65535)
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .apply { if (payload != null) post(payload.toString().toRequestBody(jsonType)) }
            .build()
        require(request.url.isHttps) { "HTTPS requis pour le contrôle d’accès." }
        val client = clientFor(socksPort)
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body ?: throw IOException("Réponse serveur vide")
                val source = body.source()
                if (source.request(65_537L)) throw IOException("Réponse serveur trop volumineuse")
                val text = source.readUtf8()
                if (!response.isSuccessful) throw BarkaBackendException(
                    runCatching { JSONObject(text).optString("detail", "Erreur serveur HTTP ${response.code}") }
                        .getOrDefault("Erreur serveur HTTP ${response.code}")
                        .replace(Regex("(?i)saspay"), "service de paiement"))
                return text
            }
        } catch (error: BarkaBackendException) {
            throw error
        } catch (error: Exception) {
            throw BarkaBackendException("Connectez-vous à un réseau, puis réessayez.")
        }
    }
}
