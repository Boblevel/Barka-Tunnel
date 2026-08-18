package com.barkatunnel.app.network

import com.barkatunnel.app.access.AccessCheckRequest
import com.barkatunnel.app.access.AccessState
import com.barkatunnel.app.access.ActivationRequest
import com.barkatunnel.app.access.ActivationResult
import com.barkatunnel.app.trial.TrialSession
import com.barkatunnel.app.trial.TrialStartRequest

interface BarkaApi {

    fun checkAccess(
        request: AccessCheckRequest
    ): ApiResult<AccessState>

    fun startTrial(
        request: TrialStartRequest
    ): ApiResult<TrialSession>

    fun activateCode(
        request: ActivationRequest
    ): ApiResult<ActivationResult>
}
