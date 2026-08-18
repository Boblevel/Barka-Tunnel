package com.barkatunnel.app.vpn

import com.barkatunnel.app.access.AccessProvider

class ProtectedVpnController(
    private val vpnController: VpnController,
    private val accessProvider: AccessProvider
) {

    fun connect(configText: String): VpnAccessResult {
        val access = accessProvider.getAccessState()

        if (!access.allowed) {
            return VpnAccessResult.AccessDenied
        }

        return when (val result = vpnController.connect(configText)) {
            is VpnConnectionResult.Connected ->
                VpnAccessResult.Connected

            is VpnConnectionResult.Error ->
                VpnAccessResult.Error(result.message)

            else ->
                VpnAccessResult.Error("Connexion VPN impossible")
        }
    }
}
