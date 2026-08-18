package com.barkatunnel.app.ui.home

sealed class HomeVpnResult {

    data class Connected(
        val networkName: String
    ) : HomeVpnResult()

    data object Disconnected : HomeVpnResult()

    data object AccessDenied : HomeVpnResult()

    data class Error(
        val message: String
    ) : HomeVpnResult()
}
