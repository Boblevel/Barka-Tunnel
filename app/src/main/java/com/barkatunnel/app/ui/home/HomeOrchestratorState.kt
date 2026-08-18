package com.barkatunnel.app.ui.home

data class HomeOrchestratorState(
    val screen: HomeScreenState = HomeScreenState(),
    val connectionSeconds: Long = 0L
)
