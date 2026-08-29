package com.barkatunnel.app.vpnc6

import android.content.Context
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import java.util.concurrent.TimeUnit

class SlowDnsEngine(
    private val context: Context,
    private val config: VpnProfileConfig.SlowDns,
    private val socksPort: Int,
    private val isCancelled: () -> Boolean = { false }
) : C6ProtocolEngine {
    override val socksAddress: String = "127.0.0.1:$socksPort"

    private var dnsttProcess: Process? = null
    private var dnsttLogFile: java.io.File? = null
    private var sshProxy: SshSocksProxy? = null

    override fun start() {
        val dnstt = NativeCoreLocator.dnstt(context)
        if (!dnstt.canExecute() && !dnstt.setExecutable(true, false)) {
            throw IllegalStateException("Le moteur SlowDNS Android n’est pas exécutable.")
        }

        val resolvers = listOf(config.dns, config.host)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        var lastFailure: Exception? = null

        for ((index, resolver) in resolvers.withIndex()) {
            if (isCancelled()) throw InterruptedException("Connexion annulée.")
            try {
                AppLogStore.add(this.context, "Diagnostic MOOV • essai DNSTT ${index + 1}/${resolvers.size}.")
                startWithResolver(dnstt, resolver)
                AppLogStore.add(this.context, "Diagnostic MOOV • DNSTT + SSH prêts.")
                return
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                lastFailure = error
                AppLogStore.add(
                    this.context,
                    "Diagnostic MOOV • DNSTT échec • ${sanitizeDiagnostic(error.message.orEmpty())}"
                )
                stopAttempt()
            }
        }

        if (isCancelled()) throw InterruptedException("Connexion annulée.")
        try {
            AppLogStore.add(this.context, "Diagnostic MOOV • essai SSH direct de secours.")
            startDirectSshFallback()
            AppLogStore.add(this.context, "Diagnostic MOOV • SSH direct de secours actif.")
            return
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            lastFailure = error
            stopAttempt()
        }

        throw IllegalStateException(
            lastFailure?.message ?: "SlowDNS n’a pas pu établir le relais DNS/SSH."
        )
    }

    private fun startWithResolver(dnstt: java.io.File, resolver: String) {
        val dnsttPort = 22_220
        val output = java.io.File(context.cacheDir, "c6_dnstt_${System.nanoTime()}.log")
        dnsttLogFile = output
        dnsttProcess = ProcessBuilder(
            dnstt.absolutePath,
            "-udp", "$resolver:53",
            "-pubkey", config.publicKey,
            config.nameServer,
            "127.0.0.1:$dnsttPort"
        )
            .redirectErrorStream(true)
            .redirectOutput(output)
            .start()

        val dnsttReady = PortWaiter.waitUntilOpen(
            "127.0.0.1",
            dnsttPort,
            DNSTT_READY_TIMEOUT_MS
        ) {
            !isCancelled() && dnsttProcess?.isAlive == true
        }
        if (!dnsttReady) {
            if (isCancelled()) throw InterruptedException("Connexion annulée.")
            val detail = dnsttFailureDetail()
            throw IllegalStateException(
                if (dnsttProcess?.isAlive != true) {
                    if (detail.isBlank()) "DNSTT s’est arrêté avant d’ouvrir son relais local."
                    else "DNSTT s’est arrêté prématurément : $detail"
                } else {
                    if (detail.isBlank()) "DNSTT n’a pas établi son relais local."
                    else "DNSTT n’a pas établi son relais local : $detail"
                }
            )
        }
        if (dnsttProcess?.isAlive != true) {
            val detail = dnsttFailureDetail()
            throw IllegalStateException(
                if (detail.isBlank()) "DNSTT s’est arrêté prématurément."
                else "DNSTT s’est arrêté prématurément : $detail"
            )
        }

        sshProxy = SshSocksProxy(
            sshHost = "127.0.0.1",
            sshPort = dnsttPort,
            username = config.username,
            password = config.password,
            localPort = socksPort
        ).also { it.start() }

        if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 8_000) { !isCancelled() }) {
            if (isCancelled()) throw InterruptedException("Connexion annulée.")
            throw IllegalStateException("SSH SlowDNS n’a pas ouvert son proxy local.")
        }
    }

    private fun startDirectSshFallback() {
        val hosts = listOf(config.host, config.sshDomain)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        var lastFailure: Exception? = null

        for (host in hosts) {
            if (isCancelled()) throw InterruptedException("Connexion annulée.")
            val candidate = SshSocksProxy(
                sshHost = host,
                sshPort = config.sshPort,
                username = config.username,
                password = config.password,
                localPort = socksPort
            )
            try {
                candidate.start()
                if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 8_000) { !isCancelled() }) {
                    if (isCancelled()) throw InterruptedException("Connexion annulée.")
                    throw IllegalStateException("SSH de secours n’a pas ouvert son proxy local.")
                }
                sshProxy = candidate
                return
            } catch (error: Exception) {
                if (error is InterruptedException) {
                    candidate.stop()
                    throw error
                }
                lastFailure = error
                candidate.stop()
            }
        }

        throw IllegalStateException(
            lastFailure?.message ?: "SSH de secours n’a pas pu joindre le serveur."
        )
    }

    private fun dnsttFailureDetail(): String {
        val output = dnsttLogFile?.takeIf { it.isFile }?.readText().orEmpty()
        if (output.isBlank()) return ""
        return sanitizeDiagnostic(
            output
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(3)
                .joinToString(" | ")
        ).take(300)
    }

    private fun sanitizeDiagnostic(value: String): String {
        var clean = value
        config.publicKey.takeIf { it.isNotBlank() }?.let {
            clean = clean.replace(it, "[clé masquée]", ignoreCase = true)
        }
        config.password.takeIf { it.isNotBlank() }?.let {
            clean = clean.replace(it, "[mot de passe masqué]")
        }
        return clean.take(300)
    }

    private fun stopAttempt() {
        sshProxy?.stop()
        sshProxy = null
        dnsttProcess?.destroy()
        runCatching { dnsttProcess?.waitFor(700, TimeUnit.MILLISECONDS) }
        if (dnsttProcess?.isAlive == true) dnsttProcess?.destroyForcibly()
        dnsttProcess = null
        dnsttLogFile?.delete()
        dnsttLogFile = null
    }

    override fun stop() {
        stopAttempt()
    }

    companion object {
        private const val DNSTT_READY_TIMEOUT_MS = 25_000L
    }
}
