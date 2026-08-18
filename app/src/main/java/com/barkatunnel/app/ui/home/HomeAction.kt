package com.barkatunnel.app.ui.home

sealed class HomeAction {

    data class SelectNetwork(
        val network: NetworkOption
    ) : HomeAction()

    data object StartFreeTrial : HomeAction()

    data object RefreshServers : HomeAction()

    data object Connect : HomeAction()

    data object Disconnect : HomeAction()
}
