package com.barkatunnel.app.vpnc6

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Loopback HTTP proxy preserving configured CONNECT headers without implicit additions. */
internal class HttpConnectBridge(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val headers: Map<String, String>,
    private val reportFailure: (String) -> Unit = {}
) : AutoCloseable {
    private val listener = ServerSocket()
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = Executors.newFixedThreadPool(128)
    private val permits = Semaphore(64)
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
        var stage = "requête locale"
        var responseSent = false
        try {
            check(!closed)
            client.soTimeout = 30_000
            val request = readHeader(client.getInputStream()).toString(Charsets.ISO_8859_1)
            require(request.substringBefore("\r\n") == "CONNECT $authority HTTP/1.1")
            stage = "connexion au proxy"
            remote.connect(InetSocketAddress(proxyHost, proxyPort), 15_000)
            check(!closed)
            remote.soTimeout = 30_000
            remote.getOutputStream().write(connectRequest())
            remote.getOutputStream().flush()
            stage = "réponse CONNECT"
            val response = readHeader(remote.getInputStream())
            val status = response.toString(Charsets.ISO_8859_1).substringBefore("\r\n").split(' ')
            require(status.size >= 2 && status[0] in listOf("HTTP/1.0", "HTTP/1.1") && status[1].matches(Regex("[1-5][0-9]{2}")))
            client.getOutputStream().write(response)
            client.getOutputStream().flush()
            responseSent = true
            if (status[1] != "200") {
                if (!closed) reportFailure("proxy HTTP : statut ${status[1]}")
                return
            }
            stage = "relais du tunnel"
            client.soTimeout = 0; remote.soTimeout = 0
            val upload = workers.submit {
                try { client.getInputStream().copyTo(remote.getOutputStream()); runCatching { remote.shutdownOutput() } }
                catch (_: Exception) { runCatching { remote.close() } }
            }
            try { remote.getInputStream().copyTo(client.getOutputStream()) }
            finally { runCatching { client.close() }; runCatching { remote.close() }; upload.cancel(true) }
        } catch (error: Exception) {
            if (!closed) runCatching { reportFailure("$stage : ${error.javaClass.simpleName}") }
            if (!responseSent) runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
        } finally { sockets.remove(remote); runCatching { remote.close() } }
    }

    private val authority get() = (if (':' in targetHost) "[$targetHost]" else targetHost) + ":$targetPort"

    internal fun connectRequest(): ByteArray {
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
        runCatching { listener.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
