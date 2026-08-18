package com.barkatunnel.app.access

import com.barkatunnel.app.network.ApiResult

class AccessValidator(
    private val coordinator: AccessCoordinator
) {

    fun validate(): AccessActionResult {
        return when (val result = coordinator.checkAccess()) {
            is ApiResult.Success -> {
                if (result.data.allowed) {
                    AccessActionResult.Allowed(result.data)
                } else {
                    AccessActionResult.Denied("Accès expiré ou non activé")
                }
            }

            is ApiResult.Error ->
                AccessActionResult.Error(result.message)
        }
    }
}
