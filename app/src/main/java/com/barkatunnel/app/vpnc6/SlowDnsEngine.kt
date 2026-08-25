package com.barkatunnel.app.vpnc6

import android.content.Context
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import java.util.concurrent.TimeUnit

class SlowDnsEngine(
    private val context: Context,
    private val config: VpnProfileConfig.SlowDns,
    private val socksPort: Int
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

        AppLogStore.add(
            this.context,
            "Diagnostic MOOV • config • DNS=${config.dns}:53 • NS=${config.nameServer} • serveur=${config.host} • SSH=${config.sshDomain}:${config.sshPort}."
        )

        for ((index, resolver) in resolvers.withIndex()) {
            try {
                AppLogStore.add(
                    this.context,
                    "Diagnostic MOOV • essai DNSTT ${index + 1}/${resolvers.size} • resolver=$resolver:53."
                )
                startWithResolver(dnstt, resolver)
                AppLogStore.add(this.context, "Diagnostic MOOV • DNSTT + SSH prêts.")
                return
            } catch (error: Exception) {
                lastFailure = error
                val processDetail = dnsttFailureDetail()
                val diagnostic = buildString {
                    append(diagnosticThrowable(error))
                    if (processDetail.isNotBlank()) {
                        append(" • log DNSTT: ")
                        append(processDetail)
                    }
                }.take(500)
                AppLogStore.add(
                    this.context,
                    "Diagnostic MOOV • DNSTT échec • $diagnostic"
                )
                stopAttempt()
            }
        }

        try {
            AppLogStore.add(this.context, "Diagnostic MOOV • essai SSH direct de secours.")
            startDirectSshFallback()
            AppLogStore.add(this.context, "Diagnostic MOOV • SSH direct de secours actif.")
            return
        } catch (error: Exception) {
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
        AppLogStore.add(
            this.context,
            "Diagnostic MOOV • processus DNSTT lancé • relais local attendu 127.0.0.1:$dnsttPort."
        )

        if (!PortWaiter.waitUntilOpen("127.0.0.1", dnsttPort, 25_000)) {
            val detail = dnsttFailureDetail()
            throw IllegalStateException(
                if (detail.isBlank()) "DNSTT n’a pas établi son relais local."
                else "DNSTT n’a pas établi son relais local : $detail"
            )
        }
        AppLogStore.add(
            this.context,
            "Diagnostic MOOV • relais DNSTT local ouvert • 127.0.0.1:$dnsttPort."
        )
        if (dnsttProcess?.isAlive != true) {
            val detail = dnsttFailureDetail()
            throw IllegalStateException(
                if (detail.isBlank()) "DNSTT s’est arrêté prématurément."
                else "DNSTT s’est arrêté prématurément : $detail"
            )
        }

        AppLogStore.add(
            this.context,
            "Diagnostic MOOV • début SSH via DNSTT • destination locale 127.0.0.1:$dnsttPort."
        )
        sshProxy = SshSocksProxy(
            sshHost = "127.0.0.1",
            sshPort = dnsttPort,
            username = config.username,
            password = config.password,
            localPort = socksPort
        ).also { it.start() }
        AppLogStore.add(
            this.context,
            "Diagnostic MOOV • session SSH via DNSTT établie • SOCKS attendu 127.0.0.1:$socksPort."
        )

        if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 8_000)) {
            throw IllegalStateException("SSH SlowDNS n’a pas ouvert son proxy local.")
        }
        AppLogStore.add(
            this.context,
            "Diagnostic MOOV • SOCKS SlowDNS ouvert • 127.0.0.1:$socksPort."
        )
    }

    private fun startDirectSshFallback() {
        val hosts = listOf(config.host, config.sshDomain)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        var lastFailure: Exception? = null

        for (host in hosts) {
            AppLogStore.add(
                this.context,
                "Diagnostic MOOV • secours SSH • $host:${config.sshPort}."
            )
            val candidate = SshSocksProxy(
                sshHost = host,
                sshPort = config.sshPort,
                username = config.username,
                password = config.password,
                localPort = socksPort
            )
            try {
                candidate.start()
                if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 8_000)) {
                    throw IllegalStateException("SSH de secours n’a pas ouvert son proxy local.")
                }
                sshProxy = candidate
                return
            } catch (error: Exception) {
                lastFailure = error
                AppLogStore.add(
                    this.context,
                    "Diagnostic MOOV • secours SSH échec • ${diagnosticThrowable(error)}"
                )
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

    private fun diagnosticThrowable(error: Throwable): String {
        val message = sanitizeDiagnostic(error.message.orEmpty()).ifBlank { "sans message" }
        val frame = error.stackTrace.firstOrNull()?.let { item ->
            "${item.className.substringAfterLast('.')}.${item.methodName}:${item.lineNumber}"
        }.orEmpty()
        val cause = error.cause?.takeIf { it !== error }?.let { item ->
            val causeMessage = sanitizeDiagnostic(item.message.orEmpty()).ifBlank { "sans message" }
            " • cause=${item.javaClass.simpleName}:$causeMessage"
        }.orEmpty()
        return buildString {
            append(error.javaClass.simpleName)
            append(":")
            append(message)
            if (frame.isNotBlank()) {
                append(" • at=")
                append(frame)
            }
            append(cause)
        }.take(360)
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
}
