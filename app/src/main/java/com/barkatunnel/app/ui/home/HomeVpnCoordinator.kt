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
        network: NetworkOption,
        isCancellationRequested: () -> Boolean = { false }
    ): HomeVpnResult {

        if (isCancellationRequested()) {
            return HomeVpnResult.Disconnected
        }

        val profile = try {
            profileRepository.loadForConnection(network.id)
        } catch (e: BarkaBackendException) {
            return HomeVpnResult.Error(
                e.message ?: "Profil VPN indisponible pour ${network.displayName}"
            )
        }

        if (isCancellationRequested()) {
            return HomeVpnResult.Disconnected
        }

        return when (
            val result = c6VpnController.connect(
                profile = profile,
                isCancellationRequested = isCancellationRequested
            )
        ) {
            is C6VpnResult.Connected -> HomeVpnResult.Connected(network.displayName)
            is C6VpnResult.Disconnected -> HomeVpnResult.Disconnected
            is C6VpnResult.Error -> HomeVpnResult.Error(result.message)
        }
    }

    fun disconnect(): HomeVpnResult {
        return when (val result = c6VpnController.disconnect()) {
            is C6VpnResult.Disconnected -> HomeVpnResult.Disconnected
            is C6VpnResult.Connected -> HomeVpnResult.Error("Le VPN est toujours connecté.")
            is C6VpnResult.Error -> HomeVpnResult.Error(result.message)
        }
    }
}
