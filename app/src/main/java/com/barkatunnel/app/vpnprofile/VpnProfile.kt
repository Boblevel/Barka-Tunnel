package com.barkatunnel.app.vpnprofile

data class VpnProfile(
    val networkId: String,
    val displayName: String,
    val protocol: VpnProfileProtocol,
    val enabled: Boolean,
    val priority: Int,
    val version: Int,
    val updatedAt: String,
    val configJson: String
)

data class VpnProfileMeta(
    val networkId: String,
    val displayName: String,
    val protocol: VpnProfileProtocol,
    val enabled: Boolean,
    val priority: Int,
    val version: Int,
    val updatedAt: String
)
