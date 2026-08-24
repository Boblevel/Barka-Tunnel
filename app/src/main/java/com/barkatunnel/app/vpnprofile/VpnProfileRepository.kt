package com.barkatunnel.app.vpnprofile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.backend.BarkaBackendException
import java.util.concurrent.ConcurrentHashMap

/**
 * Couche centrale des profils VPN dynamiques.
 * Les configurations sont récupérées par HTTPS lorsque le réseau est disponible,
 * puis conservées dans un cache chiffré par Android Keystore pour les connexions hors ligne.
 */
class VpnProfileRepository(
    private val backendClient: BarkaBackendClient,
    context: Context
) {

    private val memoryProfiles = ConcurrentHashMap<String, VpnProfile>()
    private val secureStore = VpnProfileSecureStore(context)
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

            val enabledProfiles = profiles.filter { it.enabled }
            var downloadedCount = 0
            var lastError: Exception? = null
            enabledProfiles.forEach { meta ->
                try {
                    fetchAndCache(meta.networkId)
                    downloadedCount += 1
                } catch (error: Exception) {
                    lastError = error
                }
            }

            if (downloadedCount != enabledProfiles.size) {
                throw BarkaBackendException(
                    lastError?.message
                        ?: "Tous les profils de connexion n’ont pas pu être synchronisés."
                )
            }

            secureStore.markSuccessfulSync()

            VpnProfileSyncResult.Success(
                profiles = profiles,
                enabledCount = downloadedCount
            )
        } catch (e: Exception) {
            VpnProfileSyncResult.Error(e.message ?: "Impossible d'actualiser les services VPN.")
        }
    }

    fun loadForConnection(networkId: String): VpnProfile {
        if (hasValidatedInternet()) {
            runCatching { fetchAndCache(networkId) }
                .getOrNull()
                ?.let { return it }
        }

        memoryProfiles[networkId]
            ?.takeIf { isUsable(networkId, it) }
            ?.let { return it }

        secureStore.load(networkId)
            ?.takeIf { isUsable(networkId, it) }
            ?.also { memoryProfiles[networkId] = it }
            ?.let { return it }

        if (!hasValidatedInternet()) {
            throw BarkaBackendException(
                "Connectez une première fois l’application à Internet pour synchroniser ce réseau."
            )
        }

        return fetchAndCache(networkId)
    }

    fun shouldRefresh(maxAgeMillis: Long): Boolean =
        System.currentTimeMillis() - secureStore.lastSuccessfulSyncAt() >= maxAgeMillis

    fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun fetchAndCache(networkId: String): VpnProfile {
        val remote = backendClient.getVpnProfile(networkId)
        if (remote.meta.networkId != networkId) {
            throw BarkaBackendException(
                "Le profil reçu ne correspond pas au réseau sélectionné."
            )
        }
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
        secureStore.save(profile)
        return profile
    }

    private fun isUsable(networkId: String, profile: VpnProfile): Boolean {
        if (
            !profile.enabled ||
            profile.networkId != networkId ||
            !VpnProfilePolicy.validate(networkId, profile.protocol)
        ) {
            return false
        }

        return runCatching {
            VpnProfileConfigParser.validate(profile.protocol, profile.configJson)
        }.isSuccess
    }

    fun inMemory(networkId: String): VpnProfile? = memoryProfiles[networkId]

    fun clearSensitiveCache() {
        memoryProfiles.clear()
    }
}
