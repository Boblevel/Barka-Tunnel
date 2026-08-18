package com.barkatunnel.app.ui.home

import com.barkatunnel.app.server.VpnServer

object NetworkServerResolver {

    fun resolve(
        network: NetworkOption,
        servers: List<VpnServer>
    ): VpnServer? {
        val keys = when (network.id) {
            "moov_bf" -> listOf("moov", "moov-africa")
            "orange_bf" -> listOf("orange")
            "telecel_bf" -> listOf("telecel")
            else -> emptyList()
        }

        return servers.firstOrNull { server ->
            server.online && keys.any { key ->
                server.name.contains(key, ignoreCase = true)
            }
        }
    }
}
