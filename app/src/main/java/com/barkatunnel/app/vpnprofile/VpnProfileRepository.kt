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

    private val appContext = context.applicationContext
    private val memoryProfiles = ConcurrentHashMap<String, VpnProfile>()
    private val secureStore = VpnProfileSecureStore(appContext)
    private val connectivityManager = appContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun refreshCatalog(): VpnProfileSyncResult = synchronized(SYNC_LOCK) {
        return@synchronized try {
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
        if (secureStore.isMaintenance(networkId)) {
            throw BarkaBackendException("Réseau en maintenance. Réessaie plus tard.")
        }

        // Une synchronisation réussie a déjà validé et chiffré le profil.
        // On l'utilise directement pour démarrer le VPN afin qu'un appel HTTPS
        // transitoire au moment du premier clic ne provoque pas un faux rejet
        // avant une nouvelle tentative automatique, sur Wi-Fi comme sur mobile.
        val cached = memoryProfiles[networkId]
            ?.takeIf { isUsable(networkId, it) }
            ?: secureStore.load(networkId)
                ?.takeIf { isUsable(networkId, it) }
                ?.also { memoryProfiles[networkId] = it }

        if (cached != null) {
            val requiredVersion = secureStore.requiredVersion(networkId)
            if (requiredVersion > cached.version) {
                if (hasInternetCapability()) {
                    try {
                        return fetchAndCache(networkId)
                    } catch (_: Exception) {
                        // La version exigée reste bloquante tant que sa récupération
                        // n'a pas réellement réussi.
                    }
                }
                throw BarkaBackendException(
                    "Mise à jour obligatoire de la configuration. Connecte Internet pour synchroniser ce réseau."
                )
            }
            return cached
        }

        // Première installation sans cache : on tente réellement le serveur,
        // même si Android n'a pas encore marqué le réseau comme VALIDATED.
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

    fun hasSuccessfulSyncForCurrentInstall(): Boolean {
        val lastSyncAt = secureStore.lastSuccessfulSyncAt()
        if (lastSyncAt <= 0L) return false

        val firstInstallAt = runCatching {
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .firstInstallTime
        }.getOrDefault(Long.MAX_VALUE)

        return lastSyncAt >= firstInstallAt
    }

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

    companion object {
        private val SYNC_LOCK = Any()
    }
}
