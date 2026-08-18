package com.barkatunnel.app.ui.home

sealed class HomeRuntimeState {

    data object LoginRequired : HomeRuntimeState()

    data class Ready(
        val runtime: HomeRuntime
    ) : HomeRuntimeState()
}
