package com.barkatunnel.app.vpnc6

import com.barkatunnel.app.vpnprofile.VpnProfileConfig

class UdpSshEngine(
    private val config: VpnProfileConfig.UdpCustom,
    private val socksPort: Int
) : C6ProtocolEngine {
    override val socksAddress: String = "127.0.0.1:$socksPort"
    private var sshProxy: SshSocksProxy? = null

    override fun start() {
        val hosts = listOfNotNull(
            config.host.takeIf { it.isNotBlank() },
            config.sshDomain?.takeIf { it.isNotBlank() }
        ).distinct()
        var lastFailure: Exception? = null

        for (host in hosts) {
            val candidate = SshSocksProxy(
                sshHost = host,
                sshPort = config.port,
                username = config.username,
                password = config.password,
                localPort = socksPort
            )
            try {
                candidate.start()
                if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 8_000)) {
                    throw IllegalStateException("SSH UDP n’a pas ouvert son proxy local.")
                }
                sshProxy = candidate
                return
            } catch (error: Exception) {
                lastFailure = error
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
}
