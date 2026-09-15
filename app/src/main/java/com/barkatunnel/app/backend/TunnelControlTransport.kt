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

    fun request(url: String, payload: JSONObject?, socksPort: Int): JSONObject {
        require(socksPort in 1..65535)
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .apply { if (payload != null) post(payload.toString().toRequestBody(jsonType)) }
            .build()
        require(request.url.isHttps) { "HTTPS requis pour le contrôle d’accès." }
        val client = clients.getOrPut(socksPort) {
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
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Contrôle serveur HTTP ${response.code}")
                val body = response.body ?: throw IOException("Réponse serveur vide")
                val source = body.source()
                if (source.request(65_537L)) throw IOException("Réponse serveur trop volumineuse")
                return JSONObject(source.readUtf8())
            }
        } catch (error: Exception) {
            throw BarkaBackendException("Impossible de joindre le serveur via le tunnel : ${error.javaClass.simpleName}")
        }
    }
}
