package com.barkatunnel.app.vpnprofile

object VpnProfilePolicy {

    private val expected = mapOf(
        "moov_tiktok" to VpnProfileProtocol.SSH_TLS,
        "moov_bf" to VpnProfileProtocol.SLOWDNS,
        "orange_bf" to VpnProfileProtocol.VLESS,
        "telecel_bf" to VpnProfileProtocol.UDP,
        "global_world" to VpnProfileProtocol.VLESS
    )

    fun expectedProtocol(networkId: String): VpnProfileProtocol? = expected[networkId]

    fun validate(networkId: String, protocol: VpnProfileProtocol): Boolean =
        expected.containsKey(networkId) && protocol in VpnProfileProtocol.entries
}
