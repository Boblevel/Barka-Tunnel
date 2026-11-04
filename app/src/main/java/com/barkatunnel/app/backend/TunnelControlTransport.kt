package com.barkatunnel.app.backend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Response
import org.json.JSONException
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
            .eventListenerFactory { call -> call.request().tag(ControlTrace::class.java) ?: EventListener.NONE }
            .build()
    }

    fun request(url: String, payload: JSONObject?, socksPort: Int): JSONObject {
        val text = requestText(url, payload, socksPort)
        return try { JSONObject(text) } catch (error: JSONException) {
            throw ControlTrace().apply { stage = "JSON" }.failure(error)
        }
    }

    fun requestArray(url: String, socksPort: Int): org.json.JSONArray {
        val text = requestText(url, null, socksPort)
        return try { org.json.JSONArray(text) } catch (error: JSONException) {
            throw ControlTrace().apply { stage = "JSON" }.failure(error)
        }
    }

    private fun requestText(url: String, payload: JSONObject?, socksPort: Int): String {
        require(socksPort in 1..65535)
        val trace = ControlTrace()
        val request = Request.Builder().url(url)
            .tag(ControlTrace::class.java, trace)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .apply { if (payload != null) post(payload.toString().toRequestBody(jsonType)) }
            .build()
        require(request.url.isHttps) { "HTTPS requis pour le contrôle d’accès." }
        val client = clientFor(socksPort)
        try {
            client.newCall(request).execute().use { response ->
                trace.stage = "lecture HTTP ${response.code}"
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
            throw trace.failure(error)
        }
    }

    /** Temporary LOT17 diagnostics. Never include URLs, exception messages or response bodies. */
    internal class ControlTrace : EventListener() {
        @Volatile var stage = "préparation"
        private val startedAt = System.nanoTime()

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            stage = "TCP/SOCKS"
        }
        override fun secureConnectStart(call: Call) { stage = "TLS API" }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) { stage = "TLS API établi" }
        override fun connectionAcquired(call: Call, connection: Connection) { stage = "connexion acquise" }
        override fun requestHeadersStart(call: Call) { stage = "envoi HTTP" }
        override fun responseHeadersStart(call: Call) { stage = "attente HTTP" }
        override fun responseHeadersEnd(call: Call, response: Response) { stage = "lecture HTTP ${response.code}" }

        internal fun failure(error: Throwable): BarkaBackendException {
            val types = mutableListOf<String>()
            var current: Throwable? = error
            repeat(3) {
                val cause = current ?: return@repeat
                types += when (cause) {
                    is java.net.SocketTimeoutException -> "SocketTimeoutException"
                    is javax.net.ssl.SSLPeerUnverifiedException -> "SSLPeerUnverifiedException"
                    is javax.net.ssl.SSLHandshakeException -> "SSLHandshakeException"
                    is javax.net.ssl.SSLException -> "SSLException"
                    is java.net.ConnectException -> "ConnectException"
                    is java.net.UnknownHostException -> "UnknownHostException"
                    is java.net.SocketException -> "SocketException"
                    is java.io.EOFException -> "EOFException"
                    is java.io.InterruptedIOException -> "InterruptedIOException"
                    is JSONException -> "JSONException"
                    is java.security.cert.CertificateException -> "CertificateException"
                    is IOException -> "IOException"
                    else -> "Exception"
                }
                current = cause.cause
            }
            return BarkaBackendException("Contrôle via tunnel • $stage • ${types.joinToString(" > ")} • ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        }
    }
}
