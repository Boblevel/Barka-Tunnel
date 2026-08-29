package com.barkatunnel.app.vpnc6

import java.net.InetSocketAddress
import java.net.Socket

object PortWaiter {
    fun waitUntilOpen(
        host: String,
        port: Int,
        timeoutMs: Long,
        shouldContinue: () -> Boolean = { true }
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted || !shouldContinue()) {
                return false
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 500)
                    return true
                }
            } catch (_: Exception) {
                if (Thread.currentThread().isInterrupted || !shouldContinue()) {
                    return false
                }
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }
}
