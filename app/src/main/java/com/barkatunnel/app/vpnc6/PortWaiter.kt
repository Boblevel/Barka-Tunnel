package com.barkatunnel.app.vpnc6

import java.net.InetSocketAddress
import java.net.Socket

object PortWaiter {
    fun waitUntilOpen(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 500)
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
        return false
    }
}
