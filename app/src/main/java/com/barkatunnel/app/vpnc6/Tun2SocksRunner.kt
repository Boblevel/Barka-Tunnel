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
        runCatching { Tun2Socks.stopTun2Socks() }
        runCatching { thread?.join(1_500) }
        thread = null
        nativeSuccess = null
        nativeFailure = null
        runCatching { descriptor?.close() }
        descriptor = null
    }
}
