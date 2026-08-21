package com.barkatunnel.app.vpnprofile

import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.backend.BarkaBackendException
import java.util.concurrent.ConcurrentHashMap

/**
 * Couche centrale des profils VPN dynamiques.
 * Les configurations sensibles ne sont jamais écrites sur le stockage du téléphone :
 * elles sont récupérées par HTTPS pour un appareil ayant un accès actif et conservées
 * uniquement en mémoire pendant l'exécution de l'application.
 */
class VpnProfileRepository(
    private val backendClient: BarkaBackendClient
) {

    private val memoryProfiles = ConcurrentHashMap<String, VpnProfile>()

    fun refreshCatalog(): VpnProfileSyncResult {
        return try {
            val profiles = backendClient.getVpnCatalog()
                .mapNotNull { item ->
                    val protocol = VpnProfileProtocol.fromServer(item.protocol)
                        ?: return@mapNotNull null
                    if (!VpnProfilePolicy.validate(item.networkId, protocol)) {
                        return@mapNotNull null
                    }
                    VpnProfileMeta(
                        networkId = item.networkId,
                        displayName = item.displayName,
                        protocol = protocol,
                        enabled = item.enabled,
                        priority = item.priority,
                        version = item.version,
                        updatedAt = item.updatedAt
                    )
                }
                .sortedWith(compareBy<VpnProfileMeta> { it.priority }.thenBy { it.networkId })

            VpnProfileSyncResult.Success(
                profiles = profiles,
                enabledCount = profiles.count { it.enabled }
            )
        } catch (e: BarkaBackendException) {
            VpnProfileSyncResult.Error(e.message ?: "Impossible d'actualiser les services VPN.")
        }
    }

    fun loadForConnection(networkId: String): VpnProfile {
        val remote = backendClient.getVpnProfile(networkId)
        val protocol = VpnProfileProtocol.fromServer(remote.meta.protocol)
            ?: throw BarkaBackendException("Protocole VPN inconnu.")

        if (!VpnProfilePolicy.validate(networkId, protocol)) {
            throw BarkaBackendException("Configuration VPN incohérente pour ce réseau.")
        }

        VpnProfileConfigParser.validate(protocol, remote.configJson)

        val profile = VpnProfile(
            networkId = remote.meta.networkId,
            displayName = remote.meta.displayName,
            protocol = protocol,
            enabled = remote.meta.enabled,
            priority = remote.meta.priority,
            version = remote.meta.version,
            updatedAt = remote.meta.updatedAt,
            configJson = remote.configJson
        )
        memoryProfiles[networkId] = profile
        return profile
    }

    fun inMemory(networkId: String): VpnProfile? = memoryProfiles[networkId]

    fun clearSensitiveCache() {
        memoryProfiles.clear()
    }
}
