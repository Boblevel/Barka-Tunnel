package com.barkatunnel.app.vpnc6

import android.content.Context
import android.os.ParcelFileDescriptor
import com.LondonX.tun2socks.Tun2Socks

class Tun2SocksRunner(private val context: Context) {
    private var descriptor: ParcelFileDescriptor? = null
    private var thread: Thread? = null

    fun start(
        vpnDescriptor: ParcelFileDescriptor,
        mtu: Int,
        vpnAddress: String,
        netmask: String,
        socksAddress: String,
        udpgwAddress: String
    ) {
        val (host, portText) = socksAddress.split(':', limit = 2)
        val port = portText.toIntOrNull() ?: throw IllegalStateException("Adresse SOCKS locale invalide.")
        descriptor = vpnDescriptor
        Tun2Socks.initialize(context.applicationContext)
        thread = Thread({
            Tun2Socks.startTun2Socks(
                Tun2Socks.LogLevel.WARNING,
                vpnDescriptor,
                mtu,
                host,
                port,
                vpnAddress,
                null,
                netmask,
                false,
                listOf(
                    "--udpgw-remote-server-addr", udpgwAddress,
                    "--udpgw-transparent-dns"
                )
            )
        }, "BarkaTun2Socks").also { it.start() }

        Thread.sleep(700)
        if (thread?.isAlive != true) {
            stop()
            throw IllegalStateException("tun2socks s’est arrêté au démarrage.")
        }
    }

    fun stop() {
        runCatching { Tun2Socks.stopTun2Socks() }
        runCatching { thread?.join(1_500) }
        thread = null
        runCatching { descriptor?.close() }
        descriptor = null
    }
}
