package com.barkatunnel.app.ui.home

import com.barkatunnel.app.backend.BarkaBackendException
import com.barkatunnel.app.vpnc6.C6VpnController
import com.barkatunnel.app.vpnc6.C6VpnResult
import com.barkatunnel.app.vpnprofile.VpnProfileRepository

class HomeVpnCoordinator(
    private val profileRepository: VpnProfileRepository,
    private val c6VpnController: C6VpnController
) {

    fun connect(
        network: NetworkOption
    ): HomeVpnResult {

        val profile = try {
            profileRepository.loadForConnection(connectionProfileId(network.id))
        } catch (e: BarkaBackendException) {
            return HomeVpnResult.Error(
                e.message ?: "Profil VPN indisponible pour ${network.displayName}"
            )
        }

        return when (val result = c6VpnController.connect(profile)) {
            is C6VpnResult.Connected -> HomeVpnResult.Connected(network.displayName)
            is C6VpnResult.Disconnected -> HomeVpnResult.Disconnected
            is C6VpnResult.Error -> HomeVpnResult.Error(result.message)
        }
    }

    private fun connectionProfileId(networkId: String): String = when (networkId) {
        "moov_bf", "telecel_bf" -> "orange_bf"
        else -> networkId
    }

    fun disconnect(): HomeVpnResult {
        return when (val result = c6VpnController.disconnect()) {
            is C6VpnResult.Disconnected -> HomeVpnResult.Disconnected
            is C6VpnResult.Connected -> HomeVpnResult.Error("Le VPN est toujours connecté.")
            is C6VpnResult.Error -> HomeVpnResult.Error(result.message)
        }
    }
}
