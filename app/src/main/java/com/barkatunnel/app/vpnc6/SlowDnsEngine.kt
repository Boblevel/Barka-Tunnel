package com.barkatunnel.app.vpnc6

import android.content.Context
import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import java.util.concurrent.TimeUnit

class SlowDnsEngine(
    private val context: Context,
    private val config: VpnProfileConfig.SlowDns,
    private val socksPort: Int
) : C6ProtocolEngine {
    override val socksAddress: String = "127.0.0.1:$socksPort"

    private var dnsttProcess: Process? = null
    private var sshProxy: SshSocksProxy? = null

    override fun start() {
        val dnstt = NativeCoreLocator.dnstt(context)
        if (!dnstt.canExecute() && !dnstt.setExecutable(true, false)) {
            throw IllegalStateException("Le moteur SlowDNS Android n’est pas exécutable.")
        }
        val dnsttPort = 22_220
        dnsttProcess = ProcessBuilder(
            dnstt.absolutePath,
            "-udp", "${config.dns}:53",
            "-pubkey", config.publicKey,
            config.nameServer,
            "127.0.0.1:$dnsttPort"
        ).redirectErrorStream(true).start()

        if (!PortWaiter.waitUntilOpen("127.0.0.1", dnsttPort, 25_000)) {
            stop()
            throw IllegalStateException("DNSTT n’a pas établi son relais local.")
        }
        if (dnsttProcess?.isAlive != true) {
            stop()
            throw IllegalStateException("DNSTT s’est arrêté prématurément.")
        }

        sshProxy = SshSocksProxy(
            sshHost = "127.0.0.1",
            sshPort = dnsttPort,
            username = config.username,
            password = config.password,
            localPort = socksPort
        ).also { it.start() }

        if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 8_000)) {
            stop()
            throw IllegalStateException("SSH SlowDNS n’a pas ouvert son proxy local.")
        }
    }

    override fun stop() {
        sshProxy?.stop()
        sshProxy = null
        dnsttProcess?.destroy()
        runCatching { dnsttProcess?.waitFor(700, TimeUnit.MILLISECONDS) }
        if (dnsttProcess?.isAlive == true) dnsttProcess?.destroyForcibly()
        dnsttProcess = null
    }
}
