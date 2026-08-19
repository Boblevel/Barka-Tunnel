package com.barkatunnel.app.vpn

import com.barkatunnel.app.access.AccessProvider
import com.barkatunnel.app.backend.BarkaBackendClient

class ProtectedVpnController private constructor(
    private val vpnController: VpnController,
    private val accessCheck: () -> Boolean
) {

    constructor(
        vpnController: VpnController,
        accessProvider: AccessProvider
    ) : this(
        vpnController = vpnController,
        accessCheck = { accessProvider.getAccessState().allowed }
    )

    constructor(
        vpnController: VpnController,
        backendClient: BarkaBackendClient
    ) : this(
        vpnController = vpnController,
        accessCheck = { backendClient.isAccessAllowed() }
    )

    fun connect(configText: String): VpnAccessResult {
        if (!accessCheck()) {
            return VpnAccessResult.AccessDenied
        }

        return when (val result = vpnController.connect(configText)) {
            is VpnConnectionResult.Connected -> VpnAccessResult.Connected
            is VpnConnectionResult.Error -> VpnAccessResult.Error(result.message)
            else -> VpnAccessResult.Error("Connexion VPN impossible")
        }
    }

    fun disconnect(): VpnAccessResult {
        return when (val result = vpnController.disconnect()) {
            is VpnConnectionResult.Disconnected -> VpnAccessResult.Disconnected
            is VpnConnectionResult.Error -> VpnAccessResult.Error(result.message)
            else -> VpnAccessResult.Error("Déconnexion VPN impossible")
        }
    }
}
