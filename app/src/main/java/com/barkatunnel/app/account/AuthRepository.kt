package com.barkatunnel.app.account

import android.content.Context
import com.barkatunnel.app.device.DeviceIdentity
import com.barkatunnel.app.network.ApiResult

class AuthRepository(
    private val context: Context,
    private val api: AuthApi,
    private val sessionStore: AccountSessionStore
) {

    fun login(
        username: String,
        password: String
    ): ApiResult<LoginResult> {

        val request = LoginRequest(
            username = username.trim(),
            password = password,
            deviceId = DeviceIdentity.getDeviceId(context)
        )

        val result = api.login(request)

        if (result is ApiResult.Success && result.data.success) {
            val accountId = result.data.accountId
            val token = result.data.token

            if (!accountId.isNullOrBlank() && !token.isNullOrBlank()) {
                sessionStore.save(
                    AccountSession(
                        accountId = accountId,
                        token = token
                    )
                )
            }
        }

        return result
    }

    fun logout(): ApiResult<Boolean> {
        val session = sessionStore.get()
            ?: return ApiResult.Success(true)

        val result = api.logout(session.token)

        if (result is ApiResult.Success) {
            sessionStore.clear()
        }

        return result
    }
}
