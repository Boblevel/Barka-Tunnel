package com.barkatunnel.app.vpnc6

import java.util.concurrent.ConcurrentHashMap

object C6VpnRuntime {
    private val requests = ConcurrentHashMap<String, SettableFutureCompat<C6VpnResult>>()

    fun register(requestId: String): SettableFutureCompat<C6VpnResult> {
        val future = SettableFutureCompat<C6VpnResult>()
        requests[requestId] = future
        return future
    }

    fun complete(requestId: String?, result: C6VpnResult) {
        if (requestId.isNullOrBlank()) return
        requests.remove(requestId)?.complete(result)
    }

    fun cancel(requestId: String) {
        requests.remove(requestId)?.cancel(true)
    }
}
