package com.barkatunnel.app.account

import com.barkatunnel.app.network.ApiResult

interface AuthApi {

    fun login(
        request: LoginRequest
    ): ApiResult<LoginResult>

    fun logout(
        token: String
    ): ApiResult<Boolean>
}
