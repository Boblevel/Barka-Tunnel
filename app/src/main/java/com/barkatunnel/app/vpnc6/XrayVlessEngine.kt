package com.barkatunnel.app.vpnc6

import android.content.Context
import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class XrayVlessEngine(
    private val context: Context,
    private val config: VpnProfileConfig.Vless,
    private val socksPort: Int
) : C6ProtocolEngine {

    override val socksAddress: String = "127.0.0.1:$socksPort"
    private var process: Process? = null
    private var configFile: File? = null
    private var logFile: File? = null

    override fun start() {
        val xray = NativeCoreLocator.xray(context)
        if (!xray.canExecute() && !xray.setExecutable(true, false)) {
            throw IllegalStateException("Le moteur VLESS Android n’est pas exécutable.")
        }

        val file = File(context.cacheDir, "c6_xray_${System.nanoTime()}.json")
        file.writeText(buildConfig().toString())
        configFile = file
        val output = File(context.cacheDir, "c6_xray_${System.nanoTime()}.log")
        logFile = output

        process = ProcessBuilder(xray.absolutePath, "run", "-config", file.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(output))
            .start()

        if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 25_000)) {
            val detail = xrayFailureDetail()
            stop()
            throw IllegalStateException(
                if (detail.isBlank()) "VLESS n’a pas ouvert son proxy local."
                else "VLESS n’a pas ouvert son proxy local : $detail"
            )
        }
        if (process?.isAlive != true) {
            val detail = xrayFailureDetail()
            stop()
            throw IllegalStateException(
                if (detail.isBlank()) "Le moteur VLESS s’est arrêté prématurément."
                else "Le moteur VLESS s’est arrêté : $detail"
            )
        }
    }

    override fun stop() {
        process?.destroy()
        runCatching { process?.waitFor(700, java.util.concurrent.TimeUnit.MILLISECONDS) }
        if (process?.isAlive == true) process?.destroyForcibly()
        process = null
        configFile?.delete()
        configFile = null
        logFile?.delete()
        logFile = null
    }

    private fun xrayFailureDetail(): String {
        val output = logFile?.takeIf { it.isFile }?.readText().orEmpty()
        if (output.isBlank()) return ""
        return output
            .replace(config.uuid, "[uuid masqué]", ignoreCase = true)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(3)
            .joinToString(" | ")
            .take(300)
    }

    private fun buildConfig(): JSONObject {
        // Quand le profil fournit l'IP réelle du serveur, Xray l'utilise pour
        // la socket TCP. Le Host WebSocket et le SNI TLS restent ceux du domaine,
        // ce qui évite de dépendre de la résolution DNS du réseau mobile.
        val outboundAddress = config.ip?.takeIf { it.isNotBlank() }
            ?: config.address.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Adresse VLESS absente.")
        val user = JSONObject()
            .put("id", config.uuid)
            .put("encryption", config.encryption)

        val vnext = JSONObject()
            .put("address", outboundAddress)
            .put("port", config.port)
            .put("users", JSONArray().put(user))

        val wsSettings = JSONObject()
            .put("path", config.path)
            .put("headers", JSONObject().put("Host", config.host))

        val stream = JSONObject()
            .put("network", config.network)
            .put("security", config.security)
            .put("wsSettings", wsSettings)

        if (config.security == "tls") {
            val tlsSettings = JSONObject()
                .put("serverName", config.sni)
                .put("allowInsecure", config.allowInsecure)
            config.fingerprint?.takeIf { it.isNotBlank() }?.let {
                tlsSettings.put("fingerprint", it)
            }
            stream.put("tlsSettings", tlsSettings)
        }

        val inbound = JSONObject()
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("udp", true))

        val outbound = JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", stream)

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound))
    }
}
