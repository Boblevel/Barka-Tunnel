package com.barkatunnel.app.tools

import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class VpnShareProxyTest {
    private fun lan(): InetAddress {
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
        assumeNotNull(address)
        return address!!
    }
    private fun header(socket: Socket): String {
        val bytes = java.io.ByteArrayOutputStream(); var end = 0
        while (end != 0x0d0a0d0a) {
            val b = socket.getInputStream().read(); check(b >= 0)
            bytes.write(b); end = (end shl 8) or b; check(bytes.size() < 16384)
        }
        return bytes.toString("ISO-8859-1")
    }
    @Test fun unauthenticatedClientsCannotReachSocks() {
        val address = lan()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socks ->
            socks.soTimeout = 150
            VpnShareProxy(address, socks.localPort, "Basic secret") { true }.use { proxy ->
                proxy.start()
                Socket(address, proxy.port).use { client ->
                    client.soTimeout = 2000
                    client.getOutputStream().write("CONNECT example.org:443 HTTP/1.1\r\n\r\n".toByteArray())
                    assertTrue(header(client).startsWith("HTTP/1.1 407"))
                }
                try { socks.accept().close(); fail("Unauthenticated upstream connection") }
                catch (_: SocketTimeoutException) { }
            }
        }
    }
    @Test fun authenticatedConnectRelaysThroughSocksWithoutLocalDns() {
        val address = lan(); val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socks ->
                socks.soTimeout = 4000
                val server = executor.submit<String> {
                    socks.accept().use { peer ->
                        peer.soTimeout = 4000
                        val input = java.io.DataInputStream(peer.getInputStream()); val out = peer.getOutputStream()
                        assertEquals(5, input.readUnsignedByte())
                        val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                        out.write(byteArrayOf(5, 0)); out.flush()
                        assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte()); assertEquals(3, input.readUnsignedByte())
                        val host = ByteArray(input.readUnsignedByte()); input.readFully(host)
                        assertEquals(443, input.readUnsignedShort())
                        out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80)); out.flush()
                        val data = ByteArray(4); input.readFully(data); out.write(data); out.flush()
                        String(host)
                    }
                }
                VpnShareProxy(address, socks.localPort, "Basic secret") { true }.use { proxy ->
                    proxy.start()
                    Socket(address, proxy.port).use { client ->
                        client.soTimeout = 4000
                        client.getOutputStream().write(("CONNECT does-not-exist.invalid:443 HTTP/1.1\r\n"+
                            "Proxy-Authorization: Basic secret\r\n\r\n").toByteArray())
                        assertTrue(header(client).startsWith("HTTP/1.1 200"))
                        client.getOutputStream().write("ping".toByteArray())
                        val reply = ByteArray(4); java.io.DataInputStream(client.getInputStream()).readFully(reply)
                        assertEquals("ping", String(reply))
                    }
                    assertEquals("does-not-exist.invalid", server.get(5, TimeUnit.SECONDS))
                }
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun publicAndLoopbackBindingsAreRejected() {
        for (host in listOf("127.0.0.1", "0.0.0.0", "8.8.8.8")) {
            VpnShareProxy(InetAddress.getByName(host), 1, "Basic secret") { true }.use { proxy ->
                try { proxy.start(); fail("Unsafe binding accepted") } catch (_: IllegalArgumentException) { }
            }
        }
    }
    @Test fun disconnectedVpnRefusesToStart() {
        VpnShareProxy(InetAddress.getByName("192.168.99.1"), 1, "Basic secret") { false }.use { proxy ->
            try { proxy.start(); fail("VPN missing") } catch (_: IllegalStateException) { }
        }
    }
}
