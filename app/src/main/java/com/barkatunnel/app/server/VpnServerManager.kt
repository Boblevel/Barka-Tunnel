package com.barkatunnel.app.server

import android.content.Context
import com.barkatunnel.app.device.DeviceIdentity
import com.barkatunnel.app.network.ApiResult

class VpnServerManager(
    private val context: Context,
    private val repository: VpnServerRepository
) {

    fun getServers(): ApiResult<List<VpnServer>> {
        return repository.getServers()
    }

    fun getConfig(
        serverId: String
    ): ApiResult<VpnConfigResponse> {

        val deviceId = DeviceIdentity.getDeviceId(context)

        return repository.getConfig(
            serverId = serverId,
            deviceId = deviceId
        )
    }
}
