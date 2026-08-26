package com.barkatunnel.app.vpnprofile

sealed class VpnProfileSyncResult {
    data class Success(
        val profiles: List<VpnProfileMeta>,
        val enabledCount: Int,
        val updatedCount: Int
    ) : VpnProfileSyncResult()

    data class Error(val message: String) : VpnProfileSyncResult()
}
