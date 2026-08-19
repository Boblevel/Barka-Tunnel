package com.barkatunnel.app.vpnprofile

enum class VpnProfileProtocol {
    SLOWDNS,
    VLESS,
    UDP;

    companion object {
        fun fromServer(value: String): VpnProfileProtocol? =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}
