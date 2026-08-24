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

    override fun start() {
        val xray = NativeCoreLocator.xray(context)
        val file = File(context.cacheDir, "c6_xray_${System.nanoTime()}.json")
        file.writeText(buildConfig().toString())
        configFile = file

        process = ProcessBuilder(xray.absolutePath, "run", "-c", file.absolutePath)
            .redirectErrorStream(true)
            .start()

        if (!PortWaiter.waitUntilOpen("127.0.0.1", socksPort, 12_000)) {
            stop()
            throw IllegalStateException("VLESS n’a pas ouvert son proxy local.")
        }
        if (process?.isAlive != true) {
            stop()
            throw IllegalStateException("Le moteur VLESS s’est arrêté prématurément.")
        }
    }

    override fun stop() {
        process?.destroy()
        runCatching { process?.waitFor(700, java.util.concurrent.TimeUnit.MILLISECONDS) }
        if (process?.isAlive == true) process?.destroyForcibly()
        process = null
        configFile?.delete()
        configFile = null
    }

    private fun buildConfig(): JSONObject {
        val outboundAddress = config.ip?.takeIf { it.isNotBlank() } ?: config.address
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
            stream.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", config.sni)
                    .put("allowInsecure", config.allowInsecure)
            )
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
