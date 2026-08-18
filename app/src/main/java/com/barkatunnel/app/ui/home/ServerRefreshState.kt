package com.barkatunnel.app.ui.home

sealed class ServerRefreshState {

    data object Idle : ServerRefreshState()

    data object Loading : ServerRefreshState()

    data class Success(
        val serverCount: Int
    ) : ServerRefreshState()

    data class Error(
        val message: String
    ) : ServerRefreshState()
}
