package com.barkatunnel.app.server

import com.barkatunnel.app.network.ApiResult

interface VpnServerApi {

    fun getServers(
        token: String
    ): ApiResult<List<VpnServer>>

    fun getConfig(
        token: String,
        serverId: String,
        deviceId: String
    ): ApiResult<VpnConfigResponse>
}
