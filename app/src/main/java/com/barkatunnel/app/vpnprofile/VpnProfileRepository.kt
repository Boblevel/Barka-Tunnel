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
                        maintenance = item.maintenance,
                        priority = item.priority,
                        version = item.version,
                        updatedAt = item.updatedAt
                    )
                }
                .sortedWith(compareBy<VpnProfileMeta> { it.priority }.thenBy { it.networkId })

            profiles.forEach { meta ->
                secureStore.setMaintenance(meta.networkId, meta.maintenance)
                if (meta.version > secureStore.cachedVersion(meta.networkId)) {
                    secureStore.markRequiredVersion(meta.networkId, meta.version)
                }
            }

            val enabledProfiles = profiles.filter { it.enabled && !it.maintenance }
            var downloadedCount = 0
            var updatedCount = 0
            var lastError: Exception? = null
            enabledProfiles.forEach { meta ->
                try {
                    val before = secureStore.cachedVersion(meta.networkId)
                    fetchAndCache(meta.networkId)
                    downloadedCount += 1
                    if (meta.version > before) updatedCount += 1
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
                enabledCount = downloadedCount,
                updatedCount = updatedCount
            )
        } catch (e: Exception) {
            VpnProfileSyncResult.Error(e.message ?: "Impossible d'actualiser les services VPN.")
        }
    }

    fun loadForConnection(networkId: String): VpnProfile {
        // Sur certains téléphones, Android peut annoncer INTERNET quelques instants
        // avant de marquer le réseau VALIDATED. On tente alors le serveur réel :
        // l'appel HTTPS reste la source de vérité et évite un faux message
        // « première synchronisation » alors qu'Internet est déjà utilisable.
        if (hasInternetCapability()) {
            try {
                return fetchAndCache(networkId)
            } catch (error: Exception) {
                // Si Android n'a pas encore validé la connectivité, on laisse
                // la logique hors ligne décider avec le cache. Si Internet est
                // réellement validé, l'erreur serveur doit rester visible.
                if (hasValidatedInternet()) throw error
            }
        }

        if (secureStore.isMaintenance(networkId)) {
            throw BarkaBackendException("Réseau en maintenance. Réessaie plus tard.")
        }

        val cached = memoryProfiles[networkId]
            ?.takeIf { isUsable(networkId, it) }
            ?: secureStore.load(networkId)
                ?.takeIf { isUsable(networkId, it) }
                ?.also { memoryProfiles[networkId] = it }

        if (cached != null) {
            val requiredVersion = secureStore.requiredVersion(networkId)
            if (requiredVersion > cached.version) {
                throw BarkaBackendException(
                    "Mise à jour obligatoire de la configuration. Connecte Internet pour synchroniser ce réseau."
                )
            }
            return cached
        }

        try {
            return fetchAndCache(networkId)
        } catch (error: Exception) {
            if (hasValidatedInternet()) throw error
        }

        throw BarkaBackendException(
            "Connecte une première fois l’application à Internet pour synchroniser ce réseau."
        )
    }

    fun shouldRefresh(maxAgeMillis: Long): Boolean =
        System.currentTimeMillis() - secureStore.lastSuccessfulSyncAt() >= maxAgeMillis

    fun hasInternetCapability(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

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
            maintenance = remote.meta.maintenance,
            priority = remote.meta.priority,
            version = remote.meta.version,
            updatedAt = remote.meta.updatedAt,
            configJson = remote.configJson
        )
        memoryProfiles[networkId] = profile
        secureStore.save(profile)
        secureStore.setMaintenance(networkId, remote.meta.maintenance)
        secureStore.clearRequiredVersion(networkId)
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

    fun isMaintenance(networkId: String): Boolean = secureStore.isMaintenance(networkId)

    fun clearSensitiveCache() {
        memoryProfiles.clear()
    }
}
