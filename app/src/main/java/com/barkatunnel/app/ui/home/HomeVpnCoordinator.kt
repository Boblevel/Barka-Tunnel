package com.barkatunnel.app.ui.home

import com.barkatunnel.app.backend.BarkaBackendException
import com.barkatunnel.app.vpn.ProtectedVpnController
import com.barkatunnel.app.vpn.VpnAccessResult
import com.barkatunnel.app.vpnprofile.VpnProfileProtocol
import com.barkatunnel.app.vpnprofile.VpnProfileRepository

class HomeVpnCoordinator(
    private val profileRepository: VpnProfileRepository,
    private val protectedVpnController: ProtectedVpnController
) {

    fun connect(
        network: NetworkOption
    ): HomeVpnResult {

        val profile = try {
            profileRepository.loadForConnection(network.id)
        } catch (e: BarkaBackendException) {
            return HomeVpnResult.Error(
                e.message ?: "Profil VPN indisponible pour ${network.displayName}"
            )
        }

        // C4.1 : le chemin de connexion utilise désormais le profil dynamique du panel.
        // Les moteurs natifs SLOWDNS/VLESS/UDP sont branchés dans l'étape core suivante ;
        // on ne retombe plus silencieusement sur l'ancien catalogue WireGuard.
        return when (profile.protocol) {
            VpnProfileProtocol.SLOWDNS ->
                HomeVpnResult.Error("Profil SlowDNS reçu. Moteur SlowDNS requis pour établir le tunnel.")

            VpnProfileProtocol.VLESS ->
                HomeVpnResult.Error("Profil VLESS reçu. Moteur VLESS requis pour établir le tunnel.")

            VpnProfileProtocol.UDP ->
                HomeVpnResult.Error("Profil UDP reçu. Moteur UDP/UDPGW requis pour établir le tunnel.")
        }
    }

    fun disconnect(): HomeVpnResult {
        return when (val result = protectedVpnController.disconnect()) {
            is VpnAccessResult.Disconnected ->
                HomeVpnResult.Disconnected

            is VpnAccessResult.Connected ->
                HomeVpnResult.Error("Le VPN est toujours connecté.")

            is VpnAccessResult.AccessDenied ->
                HomeVpnResult.Error("Déconnexion refusée.")

            is VpnAccessResult.Error ->
                HomeVpnResult.Error(result.message)
        }
    }
}
