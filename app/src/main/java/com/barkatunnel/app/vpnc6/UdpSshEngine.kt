package com.barkatunnel.app.vpnc6

import android.content.Context
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.vpnprofile.VpnProfileConfig

class UdpSshEngine(
    private val context: Context,
    private val config: VpnProfileConfig.UdpCustom,
    private val socksPort: Int,
    private val isCancelled: () -> Boolean = { false }
) : C6ProtocolEngine {
    override val socksAddress: String = "127.0.0.1:$socksPort"
    private var sshProxy: SshSocksProxy? = null

    override fun start() {
        val hosts = listOfNotNull(
            config.host.takeIf { it.isNotBlank() },
            config.sshDomain?.takeIf { it.isNotBlank() }
        ).distinct()
        AppLogStore.add(
            context,
            "Diagnostic TELECEL • config SSH/UDP • hôtes=${hosts.joinToString("/")} • " +
                "portSSH=${config.port} • UDPGW=${config.udpGwHost}:${config.udpGwPort} • " +
                "SOCKS=127.0.0.1:$socksPort."
        )
        var lastFailure: Exception? = null

        for (host in hosts) {
            if (isCancelled()) throw InterruptedException("Connexion annulée.")
            AppLogStore.add(context, "Diagnostic TELECEL • essai SSH • $host:${config.port}.")
            val candidate = SshSocksProxy(
                sshHost = host,
                sshPort = config.port,
                username = config.username,
                password = config.password,
                localPort = socksPort,
                isCancelled = isCancelled
            )
            sshProxy = candidate
            try {
                candidate.start()
                AppLogStore.add(
                    context,
                    "Diagnostic TELECEL • session SSH établie • SOCKS attendu 127.0.0.1:$socksPort."
                )
                if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 8_000) { !isCancelled() }) {
                    if (isCancelled()) throw InterruptedException("Connexion annulée.")
                    throw IllegalStateException("SSH UDP n’a pas ouvert son proxy local.")
                }
                AppLogStore.add(context, "Diagnostic TELECEL • SOCKS SSH ouvert • 127.0.0.1:$socksPort.")
                sshProxy = candidate
                return
            } catch (error: Exception) {
                if (error is InterruptedException) {
                    candidate.stop()
                    throw error
                }
                lastFailure = error
                AppLogStore.add(
                    context,
                    "Diagnostic TELECEL • SSH échec • ${error.javaClass.simpleName}:${error.message.orEmpty().take(180)}"
                )
                candidate.stop()
            }
        }

        throw IllegalStateException(
            lastFailure?.message ?: "SSH UDP n’a pas pu joindre le serveur."
        )
    }

    override fun stop() {
        sshProxy?.stop()
        sshProxy = null
    }

    override fun isRunning(): Boolean = sshProxy?.isRunning() == true
}
