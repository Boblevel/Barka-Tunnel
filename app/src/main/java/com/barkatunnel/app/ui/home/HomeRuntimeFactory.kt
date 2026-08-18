package com.barkatunnel.app.ui.home

import android.content.Context
import com.barkatunnel.app.access.AccessCoordinator
import com.barkatunnel.app.access.ServerAccessProvider
import com.barkatunnel.app.core.AppContainer
import com.barkatunnel.app.device.DeviceIdentity
import com.barkatunnel.app.vpn.ProtectedVpnController
import com.barkatunnel.app.vpn.VpnController

object HomeRuntimeFactory {

    fun create(
        context: Context,
        container: AppContainer
    ): HomeRuntime? {

        val session = container.sessionStore.get()
            ?: return null

        val deviceId = DeviceIdentity.getDeviceId(context)

        val accessCoordinator = AccessCoordinator(
            api = container.accessApi,
            accountId = session.accountId,
            deviceId = deviceId
        )

        val accessProvider = ServerAccessProvider(
            api = container.accessApi,
            accountId = session.accountId,
            deviceId = deviceId
        )

        val protectedVpnController = ProtectedVpnController(
            vpnController = VpnController(context),
            accessProvider = accessProvider
        )

        return HomeRuntime(
            accessController = HomeAccessController(
                coordinator = accessCoordinator
            ),
            serverController = HomeServerController(
                serverManager = container.vpnServerManager
            ),
            vpnCoordinator = HomeVpnCoordinator(
                serverManager = container.vpnServerManager,
                protectedVpnController = protectedVpnController
            )
        )
    }
}
