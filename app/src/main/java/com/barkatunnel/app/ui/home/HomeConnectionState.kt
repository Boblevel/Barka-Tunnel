package com.barkatunnel.app.ui.home

sealed class HomeConnectionState {

    data object Disconnected : HomeConnectionState()

    data object Connecting : HomeConnectionState()

    data class Connected(
        val networkName: String
    ) : HomeConnectionState()

    data class Error(
        val message: String
    ) : HomeConnectionState()
}
