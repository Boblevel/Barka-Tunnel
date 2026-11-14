package com.barkatunnel.app.vpnc6

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Loopback HTTP proxy preserving configured CONNECT headers without implicit additions. */
internal class HttpConnectBridge(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val headers: Map<String, String>,
    private val payloadTemplate: String? = null,
    private val reportFailure: (String) -> Unit = {}
) : AutoCloseable {
    private val listener = ServerSocket()
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = Executors.newFixedThreadPool(128)
    private val permits = Semaphore(64)
    private val requestReported = AtomicBoolean(false)
    private val retryAfterNanos = java.util.concurrent.atomic.AtomicLong()
    private val attempts = AtomicInteger()
    private val accepted = AtomicInteger()
    private val limited = AtomicInteger()
    private val rejected = AtomicInteger()
    private val summaryReported = AtomicBoolean(false)
    @Volatile private var closed = false
    val port: Int get() = listener.localPort

    fun start() {
        listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
        Thread({
            try {
                while (!closed) {
                    val client = listener.accept()
                    if (closed || !permits.tryAcquire()) { client.close(); continue }
                    sockets.add(client)
                    try { workers.execute {
                        try { relay(client) } finally {
                            sockets.remove(client); runCatching { client.close() }; permits.release()
                        }
                    } } catch (_: java.util.concurrent.RejectedExecutionException) {
                        sockets.remove(client); client.close(); permits.release()
                    }
                }
            } catch (_: Exception) { close() }
        }, "barka-connect-bridge").apply { isDaemon = true; start() }
    }

    private fun relay(client: Socket) {
        val remote = Socket()
        sockets.add(remote)
        val requestId = attempts.incrementAndGet()
        var stage = "requête locale"
        var responseSent = false
        try {
            check(!closed)
            client.soTimeout = 30_000
            val request = readHeader(client.getInputStream()).toString(Charsets.ISO_8859_1)
            require(request.substringBefore("\r\n") == "CONNECT $authority HTTP/1.1")
            while (!closed && System.nanoTime() < retryAfterNanos.get()) Thread.sleep(100)
            check(!closed)
            stage = "connexion au proxy"
            val startedAt = System.nanoTime()
            remote.connect(InetSocketAddress(proxyHost, proxyPort), 15_000)
            val tcpMs = (System.nanoTime() - startedAt) / 1_000_000
            check(!closed)
            remote.soTimeout = 30_000
            val payload = connectRequest()
            if (requestReported.compareAndSet(false, true)) {
                runCatching {
                    reportFailure(requestDiagnostic(payload))
                    reportFailure("route • proxy=${ConnectionDiagnostics.host(proxyHost)}:$proxyPort • cible=${ConnectionDiagnostics.host(targetHost)}:$targetPort")
                    headers.entries.forEachIndexed { index, entry ->
                        reportFailure("en-tête ${index + 1}/${headers.size} • ${ConnectionDiagnostics.header(entry.key, entry.value)}")
                    }
                }
            }
            remote.getOutputStream().write(payload)
            remote.getOutputStream().flush()
            stage = "réponse CONNECT"
            val response = readHeader(remote.getInputStream())
            val status = response.toString(Charsets.ISO_8859_1).substringBefore("\r\n").split(' ')
            require(status.size >= 2 && status[0] in listOf("HTTP/1.0", "HTTP/1.1") && status[1].matches(Regex("[1-5][0-9]{2}")))
            client.getOutputStream().write(response)
            client.getOutputStream().flush()
            responseSent = true
            if (status[1] != "200") {
                rejected.incrementAndGet()
                if (status[1] == "429") {
                    val count = limited.incrementAndGet()
                    val retry = response.toString(Charsets.ISO_8859_1).split("\r\n").firstOrNull { it.substringBefore(':').equals("Retry-After",true) }?.substringAfter(':')?.trim()?.toLongOrNull()
                    val seconds = retry?.coerceIn(1,300) ?: (2L*count).coerceAtMost(10)
                    val deadline=System.nanoTime()+seconds*1_000_000_000L
                    retryAfterNanos.updateAndGet { old -> maxOf(old,deadline) }
                }
                if (!closed) runCatching {
                    if (requestId == 1) {
                        val fields = response.toString(Charsets.ISO_8859_1).split("\r\n").drop(1)
                        val known = listOf("Server", "Via", "Retry-After", "Content-Type", "Content-Length", "Proxy-Authenticate")
                            .filter { name -> fields.any { it.substringBefore(':').equals(name, true) } }
                        reportFailure("réponse proxy • octets en-tête=${response.size} • champs présents=${known.joinToString(",").ifEmpty { "aucun champ reconnu" }} • contenu/valeurs masqués")
                    }
                    if (requestId <= 12) reportFailure("proxy HTTP : statut ${status[1]} • délai=${(System.nanoTime() - startedAt) / 1_000_000} ms • requête=$requestId • TCP=${tcpMs} ms • ${responseDiagnostic(response)}")
                }
                return
            }
            accepted.incrementAndGet()
            if (requestId <= 12) runCatching { reportFailure("requête=$requestId • CONNECT 200 accepté • TCP=${tcpMs} ms • total=${(System.nanoTime() - startedAt) / 1_000_000} ms • TLS/WS pas encore confirmés") }
            stage = "relais du tunnel"
            client.soTimeout = 0; remote.soTimeout = 0
            val upload = workers.submit {
                try { client.getInputStream().copyTo(remote.getOutputStream()); runCatching { remote.shutdownOutput() } }
                catch (_: Exception) { runCatching { remote.close() } }
            }
            try { remote.getInputStream().copyTo(client.getOutputStream()) }
            finally { runCatching { client.close() }; runCatching { remote.close() }; upload.cancel(true) }
        } catch (error: Exception) {
            if (!closed && requestId <= 12) runCatching { reportFailure("requête=$requestId • $stage : ${error.javaClass.simpleName}") }
            if (!responseSent) runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
        } finally { sockets.remove(remote); runCatching { remote.close() } }
    }

    private val authority get() = (if (':' in targetHost) "[$targetHost]" else targetHost) + ":$targetPort"

    // Temporary diagnostics: no raw destination, header values or response body.
    internal fun requestDiagnostic(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "requête CONNECT • octets=${payload.size} • SHA256=$digest"
    }

    internal fun responseDiagnostic(response: ByteArray): String {
        val retry = response.toString(Charsets.ISO_8859_1).split("\r\n")
            .drop(1).firstOrNull { it.substringBefore(':').equals("Retry-After", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val safeRetry = when {
            retry == null -> "absent"
            retry.matches(Regex("[0-9]{1,9}")) -> "$retry s"
            else -> "présent (valeur masquée)"
        }
        return "Retry-After=$safeRetry"
    }

    internal fun connectRequest(): ByteArray {
        payloadTemplate?.let { return com.barkatunnel.app.vpnprofile.ConnectPayload.expand(it,targetHost,targetPort) }
        require(targetHost.isNotBlank() && targetHost.none { it.isWhitespace() } && targetPort in 1..65535)
        require(headers.size <= 12)
        headers.forEach { (key, value) ->
            require(Regex("[A-Za-z0-9-]{1,40}").matches(key) && value.length <= 200 && '\r' !in value && '\n' !in value)
        }
        return ("CONNECT $authority HTTP/1.1\r\n" +
            headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    private fun readHeader(input: InputStream): ByteArray {
        val bytes = ByteArrayOutputStream(); var end = 0
        while (bytes.size() < 16_384) {
            val next = input.read(); check(next >= 0)
            bytes.write(next); end = (end shl 8) or next
            if (end == 0x0d0a0d0a) return bytes.toByteArray()
        }
        error("HTTP header too large")
    }

    override fun close() {
        closed = true
        if (summaryReported.compareAndSet(false, true)) runCatching {
            reportFailure("bilan • requêtes=${attempts.get()} • acceptées=${accepted.get()} • refusées=${rejected.get()} • HTTP429=${limited.get()}")
            if (limited.get() > 0 && accepted.get() == 0) reportFailure("blocage observé : proxy HTTP 429 avant TLS/WS/VLESS • motif interne non déterminé")
        }
        runCatching { listener.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
