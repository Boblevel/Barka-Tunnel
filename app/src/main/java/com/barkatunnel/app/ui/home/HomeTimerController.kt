package com.barkatunnel.app.ui.home

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class HomeTimerController(
    private val onAccessTick: (Long) -> Unit,
    private val onConnectionTick: (Long) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())

    private var accessBaseSeconds = 0L
    private var accessStartedElapsed = 0L
    private var connectionStartedElapsed = 0L
    private var connectionRunning = false

    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000L)
        }
    }

    fun start() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
    }

    /**
     * remainingSeconds vient du serveur.
     * SystemClock.elapsedRealtime() sert seulement à animer l'affichage entre deux synchronisations.
     * Il ne décide jamais si l'accès est autorisé.
     */
    fun syncAccessRemaining(remainingSeconds: Long) {
        accessBaseSeconds = remainingSeconds.coerceAtLeast(0L)
        accessStartedElapsed = SystemClock.elapsedRealtime()
        onAccessTick(accessBaseSeconds)
    }

    fun startConnectionTimer() {
        connectionStartedElapsed = SystemClock.elapsedRealtime()
        connectionRunning = true
        onConnectionTick(0L)
    }

    fun stopConnectionTimer() {
        connectionRunning = false
        onConnectionTick(0L)
    }

    private fun update() {
        if (accessStartedElapsed > 0L) {
            val elapsedSeconds =
                (SystemClock.elapsedRealtime() - accessStartedElapsed) / 1000L

            onAccessTick(
                (accessBaseSeconds - elapsedSeconds)
                    .coerceAtLeast(0L)
            )
        }

        if (connectionRunning) {
            val connectedSeconds =
                (SystemClock.elapsedRealtime() - connectionStartedElapsed) / 1000L

            onConnectionTick(connectedSeconds)
        }
    }
}
