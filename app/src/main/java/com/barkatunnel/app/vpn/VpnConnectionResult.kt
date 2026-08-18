package com.barkatunnel.app.vpn

sealed class VpnConnectionResult {

    data object Connected : VpnConnectionResult()

    data object Disconnected : VpnConnectionResult()

    data class Error(
        val message: String
    ) : VpnConnectionResult()
}
