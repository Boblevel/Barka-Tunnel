package com.barkatunnel.app.vpnc6

import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Optional panel presence: never runs network work on the VPN or UI thread. */
internal class VpnPresenceReporter(
    private val send: (String, Boolean, Int?) -> Unit,
    private val now: () -> Long,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private var session: String? = null
    private var nextHeartbeat = 0L
    private var pending = false
    private var closed = false

    @Synchronized fun start(): String? {
        if (closed) return null
        stop()
        session = UUID.randomUUID().toString()
        nextHeartbeat = 0L
        return session
    }

    @Synchronized fun heartbeat(expectedSession: String?, socksPort: Int?) {
        if (closed || expectedSession == null || session != expectedSession || pending) return
        val time = now()
        if (time < nextHeartbeat) return
        nextHeartbeat = time + 30_000L
        pending = true
        try {
            executor.execute {
                try {
                    val active = synchronized(this) { !closed && session == expectedSession }
                    if (active) runCatching { send(expectedSession, true, socksPort) }
                } finally {
                    synchronized(this) { pending = false }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending = false
        }
    }

    @Synchronized fun stop() {
        val previous = session ?: return
        session = null
        // Same executor: an already running heartbeat finishes before this stop.
        // A queued heartbeat sees the invalidated session and sends nothing.
        runCatching { executor.execute { runCatching { send(previous, false, null) } } }
    }

    @Synchronized fun close() {
        if (closed) return
        stop()
        closed = true
        executor.shutdown()
    }
}
