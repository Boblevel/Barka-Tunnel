package com.barkatunnel.app.vpnc6

sealed interface C6VpnResult {
    data class Connected(val protocol: String) : C6VpnResult
    data object Disconnected : C6VpnResult
    data class Error(val message: String) : C6VpnResult
}
