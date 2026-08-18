package com.barkatunnel.app.ui.home

data class HomeScreenState(
    val selectedNetwork: NetworkOption? = null,
    val access: HomeAccessState = HomeAccessState(),
    val connection: HomeConnectionState =
        HomeConnectionState.Disconnected,
    val serversLoaded: Int = 0
)
