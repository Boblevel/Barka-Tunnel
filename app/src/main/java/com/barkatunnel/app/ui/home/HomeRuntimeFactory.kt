package com.barkatunnel.app.ui.home

import android.content.Context
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.core.AppContainer
import com.barkatunnel.app.vpn.ProtectedVpnController
import com.barkatunnel.app.vpn.VpnController
import com.barkatunnel.app.vpnprofile.VpnProfileRepository

object HomeRuntimeFactory {

    fun create(
        context: Context,
        container: AppContainer
    ): HomeRuntime {

        val backendClient = BarkaBackendClient(context)

        val protectedVpnController = ProtectedVpnController(
            vpnController = VpnController(context),
            backendClient = backendClient
        )

        return HomeRuntime(
            accessController = HomeAccessController(
                backendClient = backendClient
            ),
            serverController = HomeServerController(
                serverManager = container.vpnServerManager
            ),
            vpnCoordinator = HomeVpnCoordinator(
                profileRepository = VpnProfileRepository(backendClient),
                protectedVpnController = protectedVpnController
            )
        )
    }
}
