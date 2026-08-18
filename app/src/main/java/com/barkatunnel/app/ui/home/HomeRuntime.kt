package com.barkatunnel.app.ui.home

data class HomeRuntime(
    val accessController: HomeAccessController,
    val serverController: HomeServerController,
    val vpnCoordinator: HomeVpnCoordinator
)
