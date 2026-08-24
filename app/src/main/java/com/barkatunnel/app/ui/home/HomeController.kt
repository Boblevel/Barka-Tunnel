package com.barkatunnel.app.ui.home

import com.barkatunnel.app.server.VpnServer

class HomeController(
    private val runtime: HomeRuntime
) {

    private var state = HomeScreenState()
    private var servers: List<VpnServer> = emptyList()

    fun currentState(): HomeScreenState = state

    fun selectNetwork(
        network: NetworkOption
    ): HomeControllerResult {
        state = state.copy(selectedNetwork = network)
        return HomeControllerResult.State(state)
    }

    fun refreshAccess(): HomeControllerResult {
        state = state.copy(
            access = runtime.accessController.refreshAccess()
        )
        return HomeControllerResult.State(state)
    }

    fun startFreeTrial(): HomeControllerResult {
        val access = runtime.accessController.startFreeTrial()
        state = state.copy(access = access)
        return access.notice?.let { HomeControllerResult.Message(it) }
            ?: HomeControllerResult.State(state)
    }

    fun refreshServers(): HomeControllerResult {
        val (refreshState, loadedServers) =
            runtime.serverController.refreshServers()

        return when (refreshState) {
            is ServerRefreshState.Success -> {
                servers = loadedServers
                state = state.copy(
                    serversLoaded = refreshState.serverCount
                )
                HomeControllerResult.State(state)
            }

            is ServerRefreshState.Error ->
                HomeControllerResult.Message(refreshState.message)

            else ->
                HomeControllerResult.State(state)
        }
    }

    fun connect(): HomeControllerResult {
        val network = state.selectedNetwork
            ?: return HomeControllerResult.Message(
                "Choisis d’abord un réseau."
            )

        if (!state.access.allowed) {
            return HomeControllerResult.Message(
                "Aucun accès actif."
            )
        }

        state = state.copy(
            connection = HomeConnectionState.Connecting
        )

        return when (
            val result = runtime.vpnCoordinator.connect(
                network = network
            )
        ) {
            is HomeVpnResult.Connected -> {
                state = state.copy(
                    connection = HomeConnectionState.Connected(
                        result.networkName
                    )
                )
                HomeControllerResult.State(state)
            }

            is HomeVpnResult.Disconnected -> {
                state = state.copy(
                    connection = HomeConnectionState.Connecting
                )
                HomeControllerResult.Message(CONNECTION_PENDING_MESSAGE)
            }

            is HomeVpnResult.AccessDenied -> {
                state = state.copy(
                    connection = HomeConnectionState.Connecting
                )
                HomeControllerResult.Message(CONNECTION_PENDING_MESSAGE)
            }

            is HomeVpnResult.Error -> {
                state = state.copy(
                    connection = HomeConnectionState.Connecting
                )
                HomeControllerResult.Message(CONNECTION_PENDING_MESSAGE)
            }
        }
    }

    fun disconnect(): HomeControllerResult {
        return when (val result = runtime.vpnCoordinator.disconnect()) {
            is HomeVpnResult.Disconnected -> {
                state = state.copy(
                    connection = HomeConnectionState.Disconnected
                )
                HomeControllerResult.State(state)
            }

            is HomeVpnResult.Connected ->
                HomeControllerResult.Message(
                    "Le VPN est toujours connecté."
                )

            is HomeVpnResult.AccessDenied ->
                HomeControllerResult.Message(
                    "Déconnexion refusée."
                )

            is HomeVpnResult.Error ->
                HomeControllerResult.Message(result.message)
        }
    }

    companion object {
        private const val CONNECTION_PENDING_MESSAGE =
            "Connexion en cours. Appuie sur le bouton pour arrêter puis réessaie."
    }
}
