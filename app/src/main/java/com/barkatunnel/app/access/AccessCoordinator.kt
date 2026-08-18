package com.barkatunnel.app.access

import com.barkatunnel.app.network.ApiResult
import com.barkatunnel.app.network.BarkaApi
import com.barkatunnel.app.trial.TrialSession
import com.barkatunnel.app.trial.TrialStartRequest

class AccessCoordinator(
    private val api: BarkaApi,
    private val accountId: String,
    private val deviceId: String
) {

    fun checkAccess(): ApiResult<AccessState> {
        return api.checkAccess(
            AccessCheckRequest(
                accountId = accountId,
                deviceId = deviceId
            )
        )
    }

    fun startTrial(): ApiResult<TrialSession> {
        return api.startTrial(
            TrialStartRequest(
                accountId = accountId,
                deviceId = deviceId
            )
        )
    }

    fun activateCode(code: String): ApiResult<ActivationResult> {
        return api.activateCode(
            ActivationRequest(
                accountId = accountId,
                deviceId = deviceId,
                code = code.trim()
            )
        )
    }
}
