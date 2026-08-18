package com.barkatunnel.app.ui.home

import com.barkatunnel.app.network.ApiResult
import com.barkatunnel.app.server.VpnServer
import com.barkatunnel.app.server.VpnServerManager

class HomeServerController(
    private val serverManager: VpnServerManager
) {

    fun refreshServers(): Pair<ServerRefreshState, List<VpnServer>> {
        return when (val result = serverManager.getServers()) {
            is ApiResult.Success -> {
                ServerRefreshState.Success(result.data.size) to result.data
            }

            is ApiResult.Error -> {
                ServerRefreshState.Error(result.message) to emptyList()
            }
        }
    }

    fun findServer(
        network: NetworkOption,
        servers: List<VpnServer>
    ): VpnServer? {
        return NetworkServerResolver.resolve(
            network = network,
            servers = servers
        )
    }
}
