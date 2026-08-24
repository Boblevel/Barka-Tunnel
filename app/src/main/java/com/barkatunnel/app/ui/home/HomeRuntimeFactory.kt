package com.barkatunnel.app.ui.home

import android.content.Context
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.core.AppContainer
import com.barkatunnel.app.vpnc6.C6VpnController
import com.barkatunnel.app.vpnprofile.VpnProfileRepository

object HomeRuntimeFactory {

    fun create(
        context: Context,
        container: AppContainer
    ): HomeRuntime {

        val backendClient = BarkaBackendClient(context)

        val c6VpnController = C6VpnController(context)

        return HomeRuntime(
            accessController = HomeAccessController(
                context = context,
                backendClient = backendClient
            ),
            serverController = HomeServerController(
                serverManager = container.vpnServerManager
            ),
            vpnCoordinator = HomeVpnCoordinator(
                profileRepository = VpnProfileRepository(backendClient, context),
                c6VpnController = c6VpnController
            )
        )
    }
}
