package com.barkatunnel.app.vpn

import android.content.Context
import com.wireguard.android.backend.Tunnel

class VpnController(context: Context) {

    private val manager = WireGuardManager(context)

    fun connect(configText: String): VpnConnectionResult {
        return try {
            val config = WireGuardConfigParser.parse(configText)
            val state = manager.connect(config)

            if (state == Tunnel.State.UP) {
                VpnConnectionResult.Connected
            } else {
                VpnConnectionResult.Error("Impossible de connecter le VPN")
            }
        } catch (e: Exception) {
            VpnConnectionResult.Error(
                e.message ?: "Erreur VPN inconnue"
            )
        }
    }

    fun disconnect(): VpnConnectionResult {
        return try {
            val state = manager.disconnect()

            if (state == Tunnel.State.DOWN) {
                VpnConnectionResult.Disconnected
            } else {
                VpnConnectionResult.Error("Impossible de déconnecter le VPN")
            }
        } catch (e: Exception) {
            VpnConnectionResult.Error(
                e.message ?: "Erreur VPN inconnue"
            )
        }
    }
}
