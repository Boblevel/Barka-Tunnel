package com.barkatunnel.app.ui.home

import com.barkatunnel.app.access.AccessCoordinator
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.network.ApiResult

class HomeAccessController private constructor(
    private val refreshAction: () -> HomeAccessState,
    private val trialAction: () -> HomeAccessState
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

    constructor(backendClient: BarkaBackendClient) : this(
        refreshAction = {
            try {
                val access = backendClient.checkAccess()
                HomeAccessState(
                    allowed = access.allowed,
                    remainingSeconds = access.remainingSeconds,
                    label = when {
                        access.allowed && access.accessType == "TRIAL" -> "Essai gratuit actif"
                        access.allowed -> "Accès actif"
                        else -> "Aucun temps actif"
                    }
                )
            } catch (e: Exception) {
                HomeAccessState(
                    allowed = false,
                    remainingSeconds = 0L,
                    label = e.message ?: "Serveur indisponible"
                )
            }
        },
        trialAction = {
            try {
                val trial = backendClient.startTrial()
                HomeAccessState(
                    allowed = trial.access.allowed,
                    remainingSeconds = trial.access.remainingSeconds,
                    label = when {
                        trial.access.allowed && trial.access.accessType == "TRIAL" -> "Essai gratuit actif"
                        trial.access.allowed -> "Accès actif"
                        else -> trial.message
                    }
                )
            } catch (e: Exception) {
                HomeAccessState(
                    allowed = false,
                    remainingSeconds = 0L,
                    label = e.message ?: "Serveur indisponible"
                )
            }
        }
    )

    fun refreshAccess(): HomeAccessState = refreshAction()

    fun startFreeTrial(): HomeAccessState = trialAction()
}
