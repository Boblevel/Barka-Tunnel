package com.barkatunnel.app.ui.home

import com.barkatunnel.app.network.ApiResult
import com.barkatunnel.app.server.VpnServerManager
import com.barkatunnel.app.vpn.ProtectedVpnController
import com.barkatunnel.app.vpn.VpnAccessResult

class HomeVpnCoordinator(
    private val serverManager: VpnServerManager,
    private val protectedVpnController: ProtectedVpnController
) {

    fun connect(
        network: NetworkOption,
        servers: List<com.barkatunnel.app.server.VpnServer>
    ): HomeVpnResult {

        val server = NetworkServerResolver.resolve(
            network = network,
            servers = servers
        ) ?: return HomeVpnResult.Error(
            "Aucun serveur disponible pour ${network.displayName}"
        )

        return when (val configResult = serverManager.getConfig(server.id)) {
            is ApiResult.Success -> {
                when (
                    val result = protectedVpnController.connect(
                        configResult.data.config
                    )
                ) {
                    is VpnAccessResult.Connected ->
                        HomeVpnResult.Connected(network.displayName)
is VpnAccessResult.Disconnected ->
    HomeVpnResult.Disconnected
                    is VpnAccessResult.AccessDenied ->
                        HomeVpnResult.AccessDenied

                    is VpnAccessResult.Error ->
                        HomeVpnResult.Error(result.message)
                }
            }

            is ApiResult.Error ->
                HomeVpnResult.Error(configResult.message)
        }
    }
}
