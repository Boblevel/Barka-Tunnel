package com.barkatunnel.app.ui.home

import com.barkatunnel.app.access.AccessCoordinator
import com.barkatunnel.app.network.ApiResult

class HomeAccessController(
    private val coordinator: AccessCoordinator
) {

    fun refreshAccess(): HomeAccessState {
        return when (val result = coordinator.checkAccess()) {
            is ApiResult.Success -> {
                val state = result.data

                HomeAccessState(
                    allowed = state.allowed,
                    remainingSeconds = state.remainingSeconds,
                    label = when {
                        state.allowed && state.remainingSeconds > 0L ->
                            "Accès actif"

                        state.allowed ->
                            "Accès autorisé"

                        else ->
                            "Aucun temps actif"
                    }
                )
            }

            is ApiResult.Error ->
                HomeAccessState(
                    allowed = false,
                    remainingSeconds = 0L,
                    label = result.message
                )
        }
    }

    fun startFreeTrial(): HomeAccessState {
        return when (val result = coordinator.startTrial()) {
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

            is ApiResult.Error ->
                HomeAccessState(
                    allowed = false,
                    remainingSeconds = 0L,
                    label = result.message
                )
        }
    }
}
