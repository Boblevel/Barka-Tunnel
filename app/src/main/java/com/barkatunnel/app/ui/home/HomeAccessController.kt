package com.barkatunnel.app.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.barkatunnel.app.access.AccessCoordinator
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.network.ApiResult
import com.barkatunnel.app.trial.TrialStatus

class HomeAccessController private constructor(
    private val refreshAction: () -> HomeAccessState,
    private val trialAction: () -> HomeAccessState,
    private val connectionSnapshot: () -> HomeAccessState? = { null }
) {

    constructor(coordinator: AccessCoordinator) : this(
        refreshAction = {
            when (val result = coordinator.checkAccess()) {
                is ApiResult.Success -> {
                    val state = result.data
                    HomeAccessState(
                        allowed = state.allowed,
                        remainingSeconds = state.remainingSeconds,
                        label = when {
                            state.allowed && state.remainingSeconds > 0L -> "Accès actif"
                            state.allowed -> "Accès autorisé"
                            else -> "Aucun temps actif"
                        }
                    )
                }

                is ApiResult.Error -> HomeAccessState(
                    allowed = false,
                    remainingSeconds = 0L,
                    label = result.message
                )
            }
        },
        trialAction = {
            when (val result = coordinator.startTrial()) {
                is ApiResult.Success -> {
                    val trial = result.data
                    HomeAccessState(
                        allowed = trial.remainingSeconds > 0L,
                        remainingSeconds = trial.remainingSeconds,
                        label = if (trial.remainingSeconds > 0L) {
                            "Essai gratuit actif"
                        } else {
                            "Essai indisponible ou expiré"
                        },
                        notice = when (trial.status) {
                            TrialStatus.EXPIRED ->
                                "L’essai gratuit de cet appareil a déjà été utilisé."
                            TrialStatus.BLOCKED ->
                                "L’essai gratuit n’est pas disponible sur cet appareil."
                            else -> null
                        }
                    )
                }

                is ApiResult.Error -> HomeAccessState(
                    allowed = false,
                    remainingSeconds = 0L,
                    label = result.message
                )
            }
        }
    )

    constructor(context: Context, backendClient: BarkaBackendClient) : this(
        connectionSnapshot = {
            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                HomeAccessSnapshotStore.restore(context)
            } else null
        },
        refreshAction = {
            try {
                val access = backendClient.checkAccess()
                val state = HomeAccessState(
                    allowed = access.allowed,
                    remainingSeconds = access.remainingSeconds,
                    label = when {
                        access.allowed && access.accessType == "TRIAL" -> "Essai gratuit actif"
                        access.allowed -> "Accès actif"
                        else -> "Aucun temps actif"
                    }
                )
                HomeAccessSnapshotStore.save(context, state)
                state
            } catch (e: Exception) {
                HomeAccessSnapshotStore.restore(context) ?: HomeAccessState(
                    allowed = false,
                    remainingSeconds = 0L,
                    label = e.message ?: "Serveur indisponible"
                )
            }
        },
        trialAction = {
            try {
                val trial = backendClient.startTrial()
                val state = HomeAccessState(
                    allowed = trial.access.allowed,
                    remainingSeconds = trial.access.remainingSeconds,
                    label = when {
                        trial.access.allowed && trial.access.accessType == "TRIAL" -> "Essai gratuit actif"
                        trial.access.allowed -> "Accès actif"
                        else -> trial.message
                    },
                    notice = trial.message.takeUnless { trial.startedNow }
                )
                HomeAccessSnapshotStore.save(context, state)
                state
            } catch (e: Exception) {
                HomeAccessSnapshotStore.restore(context) ?: HomeAccessState(
                    allowed = false,
                    remainingSeconds = 0L,
                    label = e.message ?: "Serveur indisponible"
                )
            }
        }
    )

    fun refreshAccess(): HomeAccessState = refreshAction()

    // This snapshot only permits starting the native proxy. BarkaVpnService
    // still checks live server authorization over SOCKS BEFORE creating TUN.
    fun prepareConnectionAccess(): HomeAccessState =
        connectionSnapshot()?.takeIf { it.allowed && it.remainingSeconds > 0L }
            ?: refreshAction()

    fun startFreeTrial(): HomeAccessState = trialAction()
}
