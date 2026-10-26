package com.barkatunnel.app.tools

import java.io.ByteArrayOutputStream
import java.net.*
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Authenticated LAN proxy. Every destination is reached exclusively through VPN SOCKS. */
class VpnShareProxy(private val address: InetAddress, private val socksPort: Int,
                    private val authorization: String, private val vpnAlive: () -> Boolean) : AutoCloseable {
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val permits = Semaphore(8)
    private val workers = Executors.newFixedThreadPool(16)
    private val listener = ServerSocket()
    @Volatile private var closed = false
    val port: Int get() = listener.localPort
    fun start() {
        require(address.isSiteLocalAddress && !address.isLoopbackAddress)
        check(vpnAlive())
        listener.bind(InetSocketAddress(address, 0), 8)
        Thread({
            try {
                while (!closed) {
                    val client = listener.accept()
                    if (closed || !vpnAlive() || !client.inetAddress.isSiteLocalAddress || !permits.tryAcquire()) {
                        client.close(); continue
                    }
                    sockets.add(client)
                    try { workers.execute { try { serve(client) } finally {
                        sockets.remove(client); runCatching { client.close() }; permits.release()
                    } } } catch (_: java.util.concurrent.RejectedExecutionException) {
                        sockets.remove(client); client.close(); permits.release()
                    }
                }
            } catch (_: Exception) { close() }
        }, "barka-share-listener").start()
    }
    private fun serve(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = client.getInputStream(); val bytes = ByteArrayOutputStream(); var end = 0
            while (bytes.size() < 16_384 && end != 0x0d0a0d0a) {
                val next = input.read(); if (next < 0) return
                bytes.write(next); end = (end shl 8) or next
            }
            require(end == 0x0d0a0d0a)
            val lines = bytes.toString("ISO-8859-1").split("\r\n")
            val request = lines.first().split(' ')
            require(request.size == 3 && request[2] in listOf("HTTP/1.0", "HTTP/1.1"))
            val headers = lines.drop(1).filter { it.isNotEmpty() }.map {
                val colon = it.indexOf(':'); require(colon > 0 && !it.first().isWhitespace())
                it.substring(0, colon).lowercase() to it.substring(colon + 1).trim()
            }
            val auth = headers.filter { it.first == "proxy-authorization" }
            if (auth.size != 1 || !MessageDigest.isEqual(auth[0].second.toByteArray(), authorization.toByteArray())) {
                client.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"Barka Tunnel\"\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").toByteArray())
                return
            }
            check(vpnAlive())
            val tunnel = request[0] == "CONNECT"
            val uri = URI(if (tunnel) "http://${request[1]}" else request[1])
            require(uri.scheme == "http" && uri.userInfo == null && uri.fragment == null && !uri.host.isNullOrBlank())
            val targetPort = if (uri.port != -1) uri.port else if (tunnel) 443 else 80
            require(targetPort in 1..65535)
            val upstream = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            sockets.add(upstream)
            try {
                check(!closed && vpnAlive())
                upstream.connect(InetSocketAddress.createUnresolved(uri.host, targetPort), 10_000)
                check(!closed && vpnAlive())
                client.soTimeout = 60_000; upstream.soTimeout = 60_000
                if (tunnel) client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                else {
                    require(request[0] in listOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"))
                    val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                    val cleaned = headers.filter { it.first !in listOf("proxy-authorization", "proxy-connection", "connection", "host") }
                    val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
                    upstream.getOutputStream().write(("${request[0]} $path HTTP/1.1\r\nHost: $host\r\n" +
                        cleaned.joinToString("") { "${it.first}: ${it.second}\r\n" } + "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                }
                val upload = workers.submit { try {
                    input.copyTo(upstream.getOutputStream(), 16_384); runCatching { upstream.shutdownOutput() }
                } catch (_: Exception) { runCatching { upstream.close() } } }
                try { upstream.getInputStream().copyTo(client.getOutputStream(), 16_384) }
                finally { runCatching { client.close() }; runCatching { upstream.close() }; upload.cancel(true) }
            } finally { sockets.remove(upstream); runCatching { upstream.close() } }
        } catch (_: Exception) {
            runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
        }
    }
    override fun close() {
        closed = true; runCatching { listener.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
