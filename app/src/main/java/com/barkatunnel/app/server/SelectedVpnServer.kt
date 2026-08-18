package com.barkatunnel.app.server

data class SelectedVpnServer(
    val server: VpnServer,
    val config: VpnConfigResponse
)
