package com.barkatunnel.app.vpnc6

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.LondonX.tun2socks.Tun2Socks

class Tun2SocksRunner(private val context: Context) {
    private var descriptor: ParcelFileDescriptor? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var nativeSuccess: Boolean? = null
    @Volatile private var nativeFailure: String? = null
    @Volatile private var nativeReady = false
    @Volatile private var stopRequested = false

    @Synchronized
    fun start(
        vpnDescriptor: ParcelFileDescriptor,
        mtu: Int,
        vpnAddress: String,
        netmask: String,
        socksAddress: String,
        udpgwAddress: String?,
        forwardUdpThroughSocks: Boolean = false
    ) {
        val (host, portText) = socksAddress.split(':', limit = 2)
        val port = portText.toIntOrNull() ?: throw IllegalStateException("Adresse SOCKS locale invalide.")
        Tun2Socks.initialize(context.applicationContext)
        val extraArgs = if (udpgwAddress.isNullOrBlank()) {
            emptyList()
        } else {
            // Le routage DNS passe déjà comme tout autre paquet UDP par UDPGW.
            // Éviter --udpgw-transparent-dns garde la ligne de commande
            // compatible avec les builds Android BadVPN utilisés par C6.
            listOf("--udpgw-remote-server-addr", udpgwAddress)
        }
        nativeSuccess = null
        nativeFailure = null
        nativeReady = false
        stopRequested = false

        val runnerThread = Thread({
            try {
                nativeSuccess = Tun2Socks.startTun2Socks(
                    Tun2Socks.LogLevel.WARNING,
                    vpnDescriptor,
                    mtu,
                    host,
                    port,
                    vpnAddress,
                    null,
                    netmask,
                    forwardUdpThroughSocks,
                    extraArgs
                )
            } catch (error: Throwable) {
                nativeFailure = error.javaClass.simpleName
            } finally {
                synchronized(nativeLifecycleLock) {
                    if (activeNativeThread === Thread.currentThread()) {
                        activeNativeThread = null
                    }
                }
            }
        }, "BarkaTun2Socks")

        synchronized(nativeLifecycleLock) {
            if (activeNativeThread?.isAlive == false) {
                activeNativeThread = null
            }
            if (activeNativeThread != null) {
                throw IllegalStateException(
                    "L’ancien pont tun2socks est toujours en cours d’arrêt."
                )
            }
            activeNativeThread = runnerThread
        }

        descriptor = vpnDescriptor
        thread = runnerThread
        try {
            runnerThread.start()
            Thread.sleep(STARTUP_STABILITY_DELAY_MS)
        } catch (error: InterruptedException) {
            stop()
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Throwable) {
            stop()
            throw error
        }

        if (!runnerThread.isAlive) {
            val detail = nativeFailure?.let { "exception native $it" }
                ?: nativeSuccess?.let { "résultat natif=$it" }
                ?: "résultat natif indisponible"
            stop()
            throw IllegalStateException("tun2socks s’est arrêté au démarrage ($detail).")
        }
        nativeReady = true
    }

    fun isRunning(): Boolean {
        val runnerThread = thread ?: return false
        return nativeReady &&
            runnerThread.isAlive &&
            nativeFailure == null &&
            synchronized(nativeLifecycleLock) {
                activeNativeThread === runnerThread
            }
    }

    @Synchronized
    fun stop() {
        val runnerThread = thread
        if (runnerThread == null) {
            closeDescriptor()
            resetStoppedState()
            return
        }

        var interrupted = false

        fun awaitExit(timeoutMs: Long): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (runnerThread.isAlive && SystemClock.elapsedRealtime() < deadline) {
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                try {
                    runnerThread.join(minOf(250L, remainingMs.coerceAtLeast(1L)))
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return !runnerThread.isAlive
        }

        try {
            if (runnerThread.isAlive && !stopRequested) {
                stopRequested = true
                runCatching { Tun2Socks.stopTun2Socks() }
            }

            if (!awaitExit(GRACEFUL_STOP_TIMEOUT_MS)) {
                // Une annulation très rapide peut arriver pendant l'entrée dans
                // le code natif. Répéter le signal garantit qu'il est reçu même
                // si le premier appel a précédé l'initialisation de sa boucle.
                runCatching { Tun2Socks.stopTun2Socks() }
                // Fermer le TUN réveille le pont natif si sa boucle n’a pas
                // répondu à la demande d’arrêt sur une ROM Android lente.
                closeDescriptor()
                if (!awaitExit(DESCRIPTOR_CLOSE_TIMEOUT_MS)) {
                    throw IllegalStateException(
                        "Le pont tun2socks précédent ne s’est pas arrêté complètement."
                    )
                }
            }
        } finally {
            if (!runnerThread.isAlive) {
                synchronized(nativeLifecycleLock) {
                    if (activeNativeThread === runnerThread) {
                        activeNativeThread = null
                    }
                }
                thread = null
                closeDescriptor()
                resetStoppedState()
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun closeDescriptor() {
        val activeDescriptor = descriptor
        descriptor = null
        runCatching { activeDescriptor?.close() }
    }

    private fun resetStoppedState() {
        nativeSuccess = null
        nativeFailure = null
        nativeReady = false
        stopRequested = false
    }

    companion object {
        private val nativeLifecycleLock = Any()
        private var activeNativeThread: Thread? = null
        private const val STARTUP_STABILITY_DELAY_MS = 800L
        private const val GRACEFUL_STOP_TIMEOUT_MS = 1_500L
        private const val DESCRIPTOR_CLOSE_TIMEOUT_MS = 3_500L
    }
}
