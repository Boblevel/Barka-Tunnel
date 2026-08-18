package com.barkatunnel.app.access

import com.barkatunnel.app.network.ApiResult
import com.barkatunnel.app.network.BarkaApi

class ServerAccessProvider(
    private val api: BarkaApi,
    private val accountId: String,
    private val deviceId: String
) : AccessProvider {

    override fun getAccessState(): AccessState {
        val request = AccessCheckRequest(
            accountId = accountId,
            deviceId = deviceId
        )

        return when (val result = api.checkAccess(request)) {
            is ApiResult.Success -> result.data

            is ApiResult.Error -> AccessState(
                allowed = false,
                type = AccessType.NONE,
                serverTime = 0L,
                expiresAt = null,
                remainingSeconds = 0L
            )
        }
    }
}
