package com.barkatunnel.app.ui.home

sealed class HomeOrchestratorEvent {

    data class Render(
        val state: HomeOrchestratorState
    ) : HomeOrchestratorEvent()

    data class Message(
        val text: String
    ) : HomeOrchestratorEvent()

    data object LoginRequired : HomeOrchestratorEvent()
}
