package com.barkatunnel.app.ui.home

sealed class HomeControllerResult {

    data class State(
        val value: HomeScreenState
    ) : HomeControllerResult()

    data class Message(
        val text: String
    ) : HomeControllerResult()

    data object LoginRequired : HomeControllerResult()
}
