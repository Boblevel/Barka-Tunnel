package com.barkatunnel.app.vpnc6

import android.content.Context
import android.os.ParcelFileDescriptor
import com.LondonX.tun2socks.Tun2Socks

class Tun2SocksRunner(private val context: Context) {
    private var descriptor: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    @Volatile private var nativeSuccess: Boolean? = null
    @Volatile private var nativeFailure: String? = null

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
        descriptor = vpnDescriptor
        synchronized(NATIVE_STATE_LOCK) {
            if (nativeBusy) {
                throw IllegalStateException("tun2socks est encore en cours d’arrêt.")
            }
            Tun2Socks.initialize(context.applicationContext)
            nativeBusy = true
        }
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
        thread = Thread({
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
                synchronized(NATIVE_STATE_LOCK) {
                    nativeBusy = false
                }
            }
        }, "BarkaTun2Socks").also { it.start() }

        Thread.sleep(1_200)
        if (thread?.isAlive != true) {
            val detail = nativeFailure?.let { "exception native $it" }
                ?: nativeSuccess?.let { "résultat natif=$it" }
                ?: "résultat natif indisponible"
            stop()
            throw IllegalStateException("tun2socks s’est arrêté au démarrage ($detail).")
        }
    }

    fun stop() {
        val activeThread = thread
        val shouldStopNative = synchronized(NATIVE_STATE_LOCK) { nativeBusy }
        if (shouldStopNative) {
            runCatching { Tun2Socks.stopTun2Socks() }
        }
        runCatching { activeThread?.join(NATIVE_STOP_TIMEOUT_MS) }
        if (activeThread?.isAlive == true) {
            nativeFailure = "arrêt natif incomplet"
        } else {
            thread = null
            nativeSuccess = null
            nativeFailure = null
        }
        runCatching { descriptor?.close() }
        descriptor = null
    }

    companion object {
        private val NATIVE_STATE_LOCK = Any()
        @Volatile private var nativeBusy = false
        private const val NATIVE_STOP_TIMEOUT_MS = 5_000L
    }
}
