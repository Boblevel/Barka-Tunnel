package com.barkatunnel.app.core

import android.content.Context
import com.barkatunnel.app.account.RealAuthApi
import com.barkatunnel.app.account.SecureAccountSessionStore
import com.barkatunnel.app.network.HttpJsonClient
import com.barkatunnel.app.network.RealBarkaApi
import com.barkatunnel.app.server.RealVpnServerApi
import com.barkatunnel.app.server.VpnServerManager
import com.barkatunnel.app.server.VpnServerRepository

class AppContainer(
    context: Context
) {

    val httpClient = HttpJsonClient()

    val sessionStore =
        SecureAccountSessionStore(context)

    val authApi =
        RealAuthApi(httpClient)

    val accessApi =
        RealBarkaApi(httpClient)

    val vpnServerApi =
        RealVpnServerApi(httpClient)

    val vpnServerRepository =
        VpnServerRepository(
            api = vpnServerApi,
            sessionStore = sessionStore
        )

    val vpnServerManager =
        VpnServerManager(
            context = context,
            repository = vpnServerRepository
        )
}
