package com.barkatunnel.app.vpnc6

import com.barkatunnel.app.vpnprofile.VpnProfileConfig

class UdpSshEngine(
    private val config: VpnProfileConfig.UdpCustom,
    private val socksPort: Int
) : C6ProtocolEngine {
    override val socksAddress: String = "127.0.0.1:$socksPort"
    private var sshProxy: SshSocksProxy? = null

    override fun start() {
        sshProxy = SshSocksProxy(
            sshHost = config.host,
            sshPort = config.port,
            username = config.username,
            password = config.password,
            localPort = socksPort
        ).also { it.start() }

        if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 8_000)) {
            stop()
            throw IllegalStateException("SSH UDP n’a pas ouvert son proxy local.")
        }
    }

    override fun stop() {
        sshProxy?.stop()
        sshProxy = null
    }
}
