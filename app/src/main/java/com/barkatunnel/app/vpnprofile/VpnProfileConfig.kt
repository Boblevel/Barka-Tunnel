package com.barkatunnel.app.vpnprofile

import com.barkatunnel.app.backend.BarkaBackendException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

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

    private fun parseSlowDns(json: JSONObject): VpnProfileConfig.SlowDns {
        val dnsResolver = requiredStringAny(json, "dns", "dns_server", "dns_resolver")
        return VpnProfileConfig.SlowDns(
            host = requiredStringAny(json, "host", "server_ip"),
            sshDomain = requiredStringAny(json, "ssh_domain", "ssh_host"),
            sshPort = requiredPortAny(json, "ssh_port", "port"),
            username = requiredString(json, "username"),
            password = requiredString(json, "password"),
            dns = resolverHost(dnsResolver),
            nameServer = requiredStringAny(json, "nameserver", "name_server", "ns", "tunnel_domain"),
            publicKey = requiredStringAny(
                json,
                "dns_public_key",
                "dnstt_public_key",
                "public_key",
                "publicKey"
            )
        )
    }

    private fun parseVless(json: JSONObject): VpnProfileConfig.Vless {
        val uri = json.optString("uri").trim()
        if (uri.isNotBlank()) return parseVlessUri(uri, json)

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

    private fun parseVlessUri(raw: String, json: JSONObject): VpnProfileConfig.Vless {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw BarkaBackendException("URI VLESS invalide.")
        }
        if (!uri.scheme.equals("vless", ignoreCase = true)) {
            throw BarkaBackendException("URI VLESS invalide.")
        }

        val uuid = uri.userInfo.orEmpty().substringBefore(':').trim()
        if (!UUID_REGEX.matches(uuid)) {
            throw BarkaBackendException("UUID VLESS invalide.")
        }
        val address = uri.host.orEmpty().trim()
        if (address.isBlank()) throw BarkaBackendException("Adresse VLESS invalide.")
        val port = uri.port
        if (port !in 1..65535) throw BarkaBackendException("Port VPN invalide : port")

        val query = parseQuery(uri.rawQuery.orEmpty())
        val network = (query["type"] ?: query["network"] ?: "").lowercase()
        if (network != "ws") {
            throw BarkaBackendException("Barka Tunnel attend VLESS WebSocket (ws).")
        }
        val security = (query["security"] ?: "none").lowercase()
        if (security !in setOf("tls", "none")) {
            throw BarkaBackendException("Sécurité VLESS invalide.")
        }
        val host = query["host"].orEmpty().ifBlank { address }
        val sni = query["sni"].orEmpty().ifBlank { host }
        val rawPath = query["path"].orEmpty().ifBlank { "/" }

        return VpnProfileConfig.Vless(
            address = address,
            ip = json.optString("ip").trim().ifBlank { null },
            port = port,
            uuid = uuid,
            encryption = query["encryption"].orEmpty().ifBlank { "none" },
            network = network,
            path = if (rawPath.startsWith('/')) rawPath else "/$rawPath",
            security = security,
            host = host,
            sni = sni,
            allowInsecure = booleanQuery(query["allowInsecure"] ?: query["insecure"])
        )
    }

    private fun parseUdp(json: JSONObject): VpnProfileConfig.UdpCustom =
        VpnProfileConfig.UdpCustom(
            host = requiredStringAny(json, "host", "server_ip"),
            sshDomain = optionalStringAny(json, "ssh_domain", "ssh_host"),
            port = requiredPortAny(json, "port", "ssh_port"),
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

    private fun requiredStringAny(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = json.optString(key).trim()
            if (value.isNotBlank()) return value
        }
        throw BarkaBackendException("Champ VPN manquant : ${keys.first()}")
    }

    private fun optionalStringAny(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = json.optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun requiredPort(json: JSONObject, key: String): Int = requiredPortAny(json, key)

    private fun requiredPortAny(json: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!json.has(key)) continue
            val raw = json.opt(key)
            val value = when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull() ?: -1
                else -> -1
            }
            if (value in 1..65535) return value
        }
        throw BarkaBackendException("Port VPN invalide : ${keys.first()}")
    }

    private fun resolverHost(value: String): String {
        val clean = value.trim()
        val colon = clean.lastIndexOf(':')
        if (colon > 0 && clean.indexOf(':') == colon && clean.substring(colon + 1).toIntOrNull() != null) {
            return clean.substring(0, colon)
        }
        return clean
    }

    private fun parseQuery(raw: String): Map<String, String> = buildMap {
        raw.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            put(key, value)
        }
    }

    private fun booleanQuery(value: String?): Boolean =
        value?.trim()?.lowercase() in setOf("1", "true", "yes")

    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )
}
