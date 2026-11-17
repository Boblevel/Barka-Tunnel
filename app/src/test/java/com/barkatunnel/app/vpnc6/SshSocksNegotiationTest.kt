package com.barkatunnel.app.vpnc6

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SshSocksNegotiationTest {
    @Test fun unsupportedAuthenticationIsRejectedBeforeOpeningAnyTunnel() {
        val proxy = SshSocksProxy("unused.invalid", 22, "unused", "unused", 0)
        val method = SshSocksProxy::class.java.getDeclaredMethod("handleClient", Socket::class.java)
            .apply { isAccessible = true }
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val task = worker.submit { server.accept().use { method.invoke(proxy, it) } }
                Socket("127.0.0.1", server.localPort).use { client ->
                    client.soTimeout = 2000
                    client.getOutputStream().write(byteArrayOf(5, 1, 2))
                    assertEquals(5, client.getInputStream().read())
                    assertEquals(255, client.getInputStream().read())
                    assertEquals(-1, client.getInputStream().read())
                }
                task.get(3, TimeUnit.SECONDS)
            }
        } finally { proxy.stop(); worker.shutdownNow() }
    }
}
