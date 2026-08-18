package com.barkatunnel.app.account

import com.barkatunnel.app.network.ApiResult

class LoginCoordinator(
    private val repository: AuthRepository
) {

    fun login(
        username: String,
        password: String
    ): LoginState {

        return when (
            val result = repository.login(
                username = username,
                password = password
            )
        ) {
            is ApiResult.Success -> {
                val data = result.data

                if (data.success && data.accountId != null) {
                    LoginState.LoggedIn(data.accountId)
                } else {
                    LoginState.Error(data.message)
                }
            }

            is ApiResult.Error ->
                LoginState.Error(result.message)
        }
    }
}
