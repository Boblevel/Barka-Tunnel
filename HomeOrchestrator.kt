package com.barkatunnel.app.ui.home

class HomeOrchestrator(
    private val controller: HomeController,
    private val timerController: HomeTimerController
) {

    private var connectionSeconds = 0L

    fun initialState(): HomeOrchestratorEvent {
        return HomeOrchestratorEvent.Render(
            HomeOrchestratorState(
                screen = controller.currentState(),
                connectionSeconds = connectionSeconds
            )
        )
    }

    fun selectNetwork(
        network: NetworkOption
    ): HomeOrchestratorEvent {
        return map(
            controller.selectNetwork(network)
        )
    }

    fun refreshAccess(): HomeOrchestratorEvent {
        val result = controller.refreshAccess()

        if (result is HomeControllerResult.State) {
            timerController.syncAccessRemaining(
                result.value.access.remainingSeconds
            )
        }

        return map(result)
    }

    fun startFreeTrial(): HomeOrchestratorEvent {
        val result = controller.startFreeTrial()

        if (result is HomeControllerResult.State) {
            timerController.syncAccessRemaining(
                result.value.access.remainingSeconds
            )
        }

        return map(result)
    }

    fun refreshServers(): HomeOrchestratorEvent {
        return map(
            controller.refreshServers()
        )
    }

    fun connect(): HomeOrchestratorEvent {
        val result = controller.connect()

        if (
            result is HomeControllerResult.State &&
            result.value.connection is HomeConnectionState.Connected
        ) {
            timerController.startConnectionTimer()
        }

        return map(result)
    }

    fun disconnect(): HomeOrchestratorEvent {
        val result = controller.disconnect()

        if (
            result is HomeControllerResult.State &&
            result.value.connection is HomeConnectionState.Disconnected
        ) {
            timerController.stopConnectionTimer()
            connectionSeconds = 0L
        }

        return map(result)
    }

    fun updateConnectionSeconds(seconds: Long) {
        connectionSeconds = seconds
    }

    private fun map(
        result: HomeControllerResult
    ): HomeOrchestratorEvent {
        return when (result) {
            is HomeControllerResult.State ->
                HomeOrchestratorEvent.Render(
                    HomeOrchestratorState(
                        screen = result.value,
                        connectionSeconds = connectionSeconds
                    )
                )

            is HomeControllerResult.Message ->
                HomeOrchestratorEvent.Message(
                    result.text
                )

            is HomeControllerResult.LoginRequired ->
                HomeOrchestratorEvent.LoginRequired
        }
    }
}
