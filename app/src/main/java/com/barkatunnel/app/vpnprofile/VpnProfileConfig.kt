package com.barkatunnel.app.vpnprofile

import com.barkatunnel.app.backend.BarkaBackendException
import org.json.JSONObject

sealed interface VpnProfileConfig {
    data class SlowDns(
        val host: String,
        val sshDomain: String,
        val sshPort: Int,
        val username: String,
        val password: String,
        val dns: String,
        val nameServer: String,
        val publicKey: String
    ) : VpnProfileConfig

    data class Vless(
        val address: String,
        val ip: String?,
        val port: Int,
        val uuid: String,
        val encryption: String,
        val network: String,
        val path: String,
        val security: String,
        val host: String,
        val sni: String,
        val allowInsecure: Boolean
    ) : VpnProfileConfig

    data class UdpCustom(
        val host: String,
        val sshDomain: String?,
        val port: Int,
        val username: String,
        val password: String,
        val udpGwHost: String,
        val udpGwPort: Int
    ) : VpnProfileConfig
}

object VpnProfileConfigParser {

    fun validate(protocol: VpnProfileProtocol, configJson: String): VpnProfileConfig =
        parse(protocol, configJson)

    fun parse(protocol: VpnProfileProtocol, configJson: String): VpnProfileConfig {
        val json = try {
            JSONObject(configJson)
        } catch (_: Exception) {
            throw BarkaBackendException("Configuration VPN JSON invalide.")
        }

        if (json.length() == 0) {
            throw BarkaBackendException("Configuration VPN vide.")
        }

        return when (protocol) {
            VpnProfileProtocol.SLOWDNS -> parseSlowDns(json)
            VpnProfileProtocol.VLESS -> parseVless(json)
            VpnProfileProtocol.UDP -> parseUdp(json)
        }
    }

    private fun parseSlowDns(json: JSONObject): VpnProfileConfig.SlowDns =
        VpnProfileConfig.SlowDns(
            host = requiredString(json, "host"),
            sshDomain = requiredString(json, "ssh_domain"),
            sshPort = requiredPort(json, "ssh_port"),
            username = requiredString(json, "username"),
            password = requiredString(json, "password"),
            dns = requiredString(json, "dns"),
            nameServer = requiredString(json, "nameserver"),
            publicKey = requiredString(json, "dns_public_key")
        )

    private fun parseVless(json: JSONObject): VpnProfileConfig.Vless {
        val uuid = requiredString(json, "uuid")
        if (!UUID_REGEX.matches(uuid)) {
            throw BarkaBackendException("UUID VLESS invalide.")
        }

        val network = requiredString(json, "network").lowercase()
        if (network != "ws") {
            throw BarkaBackendException("Barka Tunnel attend VLESS WebSocket (ws).")
        }

        val security = requiredString(json, "security").lowercase()
        if (security !in setOf("tls", "none")) {
            throw BarkaBackendException("Sécurité VLESS invalide.")
        }

        return VpnProfileConfig.Vless(
            address = requiredString(json, "address"),
            ip = json.optString("ip").trim().ifBlank { null },
            port = requiredPort(json, "port"),
            uuid = uuid,
            encryption = json.optString("encryption", "none").trim().ifBlank { "none" },
            network = network,
            path = requiredString(json, "path").let { if (it.startsWith('/')) it else "/$it" },
            security = security,
            host = requiredString(json, "host"),
            sni = requiredString(json, "sni"),
            allowInsecure = json.optBoolean("allow_insecure", false)
        )
    }

    private fun parseUdp(json: JSONObject): VpnProfileConfig.UdpCustom =
        VpnProfileConfig.UdpCustom(
            host = requiredString(json, "host"),
            sshDomain = json.optString("ssh_domain").trim().ifBlank { null },
            port = requiredPort(json, "port"),
            username = requiredString(json, "username"),
            password = requiredString(json, "password"),
            udpGwHost = json.optString("udpgw_host", "127.0.0.1").trim().ifBlank { "127.0.0.1" },
            udpGwPort = json.optInt("udpgw_port", 7300).also {
                if (it !in 1..65535) throw BarkaBackendException("Port UDPGW invalide.")
            }
        )

    private fun requiredString(json: JSONObject, key: String): String =
        json.optString(key).trim().ifBlank {
            throw BarkaBackendException("Champ VPN manquant : $key")
        }

    private fun requiredPort(json: JSONObject, key: String): Int {
        val value = json.optInt(key, -1)
        if (value !in 1..65535) {
            throw BarkaBackendException("Port VPN invalide : $key")
        }
        return value
    }

    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )
}
