package com.barkatunnel.app.vpnc6

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpConnectBridgeTest {
    @Test fun preservesPayloadAndRelaysBytesWithoutAddingHeaders() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { upstream ->
            upstream.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            val headers = linkedMapOf("X-Connected-To" to "192.0.2.2", "Proxy-Connection" to "keep-alive",
                "Connection" to "keep-alive", "User-Agent" to "FBAV/0.0", "X-Iorg-Bsid" to "test")
            val expected = "CONNECT target.invalid:443 HTTP/1.1\r\n" +
                headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
            val future = executor.submit<String> {
                upstream.accept().use { remote ->
                    remote.soTimeout = 5000
                    val bytes = java.io.ByteArrayOutputStream(); var end = 0
                    while (end != 0x0d0a0d0a) { val n = remote.getInputStream().read(); check(n >= 0); bytes.write(n); end = (end shl 8) or n }
                    // Coalesced header and tunnel bytes must not be lost.
                    remote.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\nOK".toByteArray())
                    assertEquals(42, remote.getInputStream().read())
                    bytes.toString("ISO-8859-1")
                }
            }
            try {
                HttpConnectBridge("127.0.0.1", upstream.localPort, "target.invalid", 443, headers).use { bridge ->
                    bridge.start()
                    Socket("127.0.0.1", bridge.port).use { client ->
                        client.soTimeout = 5000
                        client.getOutputStream().write("CONNECT target.invalid:443 HTTP/1.1\r\nHost: injected.invalid\r\n\r\n".toByteArray())
                        val expectedResponse = "HTTP/1.1 200 OK\r\n\r\nOK".toByteArray()
                        val response = ByteArray(expectedResponse.size)
                        java.io.DataInputStream(client.getInputStream()).readFully(response)
                        assertArrayEquals(expectedResponse, response)
                        client.getOutputStream().write(42)
                        assertEquals(expected, future.get(5, TimeUnit.SECONDS))
                    }
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun refusesADifferentDestinationAndReleasesListener() {
        val bridge = HttpConnectBridge("127.0.0.1", 9, "target.invalid", 443, emptyMap())
        bridge.start(); val port = bridge.port
        try {
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("CONNECT other.invalid:443 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(client.getInputStream().bufferedReader().readLine().contains("502"))
            }
        } finally { bridge.close() }
        try { Socket("127.0.0.1", port).close(); fail("Listener still open") } catch (_: java.net.ConnectException) { }
    }

    @Test fun preservesProxyRejectionAndReportsStatus() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { upstream ->
            upstream.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            val diagnostic = java.util.concurrent.LinkedBlockingQueue<String>()
            val future = executor.submit {
                upstream.accept().use { remote ->
                    remote.soTimeout = 5000
                    val reader = remote.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    remote.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
            try {
                HttpConnectBridge("127.0.0.1", upstream.localPort, "target.invalid", 443, emptyMap()) { diagnostic.add(it) }.use { bridge ->
                    bridge.start()
                    Socket("127.0.0.1", bridge.port).use { client ->
                        client.soTimeout = 5000
                        client.getOutputStream().write("CONNECT target.invalid:443 HTTP/1.1\r\n\r\n".toByteArray())
                        assertEquals("HTTP/1.1 403 Forbidden", client.getInputStream().bufferedReader().readLine())
                        assertEquals("proxy HTTP : statut 403", diagnostic.poll(5, TimeUnit.SECONDS))
                    }
                    future.get(5, TimeUnit.SECONDS)
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun stopClosesActiveSockets() {
        val bridge = HttpConnectBridge("127.0.0.1", 9, "target.invalid", 443, emptyMap())
        bridge.start()
        Socket("127.0.0.1", bridge.port).use { client ->
            client.soTimeout = 5000
            client.getOutputStream().write("CONNECT target.invalid".toByteArray())
            bridge.close()
            try { assertEquals(-1, client.getInputStream().read()) }
            catch (_: java.net.SocketException) { }
        }
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsHeaderInjection() {
        HttpConnectBridge("127.0.0.1", 8080, "target.invalid", 443,
            mapOf("X-Test" to "value\r\nInjected: yes")).use { it.connectRequest() }
    }
}
