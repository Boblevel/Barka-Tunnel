package com.barkatunnel.app.ui.home

import com.barkatunnel.app.server.VpnServer

class HomeController(
    private val runtime: HomeRuntime
) {

    private val stateLock = Any()
    private var state = HomeScreenState()
    private var accessGeneration = 0L
    private var servers: List<VpnServer> = emptyList()

    fun currentState(): HomeScreenState = synchronized(stateLock) { state }

    fun syncConnection(connection: HomeConnectionState): HomeControllerResult {
        return HomeControllerResult.State(
            updateState { it.copy(connection = connection) }
        )
    }

    fun syncAccess(access: HomeAccessState): HomeControllerResult {
        return HomeControllerResult.State(
            updateState { accessGeneration++; it.copy(access = access) }
        )
    }

    fun selectNetwork(
        network: NetworkOption
    ): HomeControllerResult {
        return HomeControllerResult.State(
            updateState { it.copy(selectedNetwork = network) }
        )
    }

    fun refreshAccess(): HomeControllerResult {
        val generation = synchronized(stateLock) { accessGeneration }
        val access = runtime.accessController.refreshAccess()
        return HomeControllerResult.State(
            updateState { if (generation == accessGeneration) it.copy(access = access) else it }
        )
    }

    fun startFreeTrial(): HomeControllerResult {
        val generation = synchronized(stateLock) { accessGeneration }
        val access = runtime.accessController.startFreeTrial()
        updateState { if (generation == accessGeneration) { accessGeneration++; it.copy(access = access) } else it }
        return access.notice?.let { HomeControllerResult.Message(it) }
            ?: HomeControllerResult.State(currentState())
    }

    fun refreshServers(): HomeControllerResult {
        val (refreshState, loadedServers) =
            runtime.serverController.refreshServers()

        return when (refreshState) {
            is ServerRefreshState.Success -> {
                val updatedState = synchronized(stateLock) {
                    servers = loadedServers
                    state = state.copy(serversLoaded = refreshState.serverCount)
                    state
                }
                HomeControllerResult.State(updatedState)
            }

            is ServerRefreshState.Error ->
                HomeControllerResult.Message(refreshState.message)

            else ->
                HomeControllerResult.State(currentState())
        }
    }

    fun connect(
        isCancellationRequested: () -> Boolean = { false }
    ): HomeControllerResult {
        val network = currentState().selectedNetwork
            ?: return HomeControllerResult.Message(
                "Choisis d’abord un réseau."
            )

        if (isCancellationRequested()) {
            return HomeControllerResult.State(currentState())
        }

        val generation = synchronized(stateLock) { accessGeneration }
        val refreshedAccess = runtime.accessController.prepareConnectionAccess()
        updateState { if (generation == accessGeneration) it.copy(access = refreshedAccess) else it }

        if (isCancellationRequested()) {
            return HomeControllerResult.State(currentState())
        }

        if (!currentState().access.allowed) {
            updateState { it.copy(connection = HomeConnectionState.Disconnected) }
            return HomeControllerResult.Message(
                "Aucun accès actif."
            )
        }

        updateState { it.copy(connection = HomeConnectionState.Connecting) }

        val vpnResult = runtime.vpnCoordinator.connect(
            network = network,
            isCancellationRequested = isCancellationRequested
        )

        if (isCancellationRequested()) {
            return HomeControllerResult.State(currentState())
        }

        return when (vpnResult) {
            is HomeVpnResult.Connected -> {
                HomeControllerResult.State(
                    updateState {
                        it.copy(
                            connection = HomeConnectionState.Connected(
                                vpnResult.networkName
                            )
                        )
                    }
                )
            }

            is HomeVpnResult.Disconnected -> {
                HomeControllerResult.State(
                    updateState { it.copy(connection = HomeConnectionState.Disconnected) }
                )
            }

            is HomeVpnResult.AccessDenied -> {
                updateState { it.copy(connection = HomeConnectionState.Disconnected) }
                HomeControllerResult.Message(CONNECTION_PENDING_MESSAGE)
            }

            is HomeVpnResult.Error -> {
                updateState { it.copy(connection = HomeConnectionState.Disconnected) }
                HomeControllerResult.Message(vpnResult.message)
            }
        }
    }

    fun disconnect(): HomeControllerResult {
        updateState { it.copy(connection = HomeConnectionState.Disconnecting) }
        return when (val result = runtime.vpnCoordinator.disconnect()) {
            is HomeVpnResult.Disconnected -> {
                HomeControllerResult.State(
                    updateState { it.copy(connection = HomeConnectionState.Disconnected) }
                )
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

    private fun updateState(
        transform: (HomeScreenState) -> HomeScreenState
    ): HomeScreenState = synchronized(stateLock) {
        state = transform(state)
        state
    }

    companion object {
        private const val CONNECTION_PENDING_MESSAGE =
            "Connexion en cours. Appuie sur le bouton pour arrêter puis réessaie."
    }
}
