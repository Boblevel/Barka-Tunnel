package com.barkatunnel.app.server

import com.barkatunnel.app.network.ApiResult
import com.barkatunnel.app.network.HttpJsonClient
import org.json.JSONObject

class RealVpnServerApi(
    private val client: HttpJsonClient
) : VpnServerApi {

    override fun getServers(
        token: String
    ): ApiResult<List<VpnServer>> {
        return try {
            val response = client.post(
                endpoint = "servers/list",
                json = "{}",
                token = token
            )

            if (!response.isSuccessful) {
                return ApiResult.Error("Impossible de charger les serveurs")
            }

            val body = JSONObject(response.body)
            val array = body.getJSONArray("servers")

            val servers = mutableListOf<VpnServer>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                servers.add(
                    VpnServer(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        country = item.getString("country"),
                        city = item.optString("city").ifBlank { null },
                        online = item.optBoolean("online", false)
                    )
                )
            }

            ApiResult.Success(servers)

        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Erreur réseau")
        }
    }

    override fun getConfig(
        token: String,
        serverId: String,
        deviceId: String
    ): ApiResult<VpnConfigResponse> {
        return try {
            val json = JSONObject()
                .put("serverId", serverId)
                .put("deviceId", deviceId)

            val response = client.post(
                endpoint = "servers/config",
                json = json.toString(),
                token = token
            )

            if (!response.isSuccessful) {
                return ApiResult.Error("Configuration VPN indisponible")
            }

            val body = JSONObject(response.body)

            ApiResult.Success(
                VpnConfigResponse(
                    serverId = body.getString("serverId"),
                    config = body.getString("config")
                )
            )

        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Erreur réseau")
        }
    }
}
