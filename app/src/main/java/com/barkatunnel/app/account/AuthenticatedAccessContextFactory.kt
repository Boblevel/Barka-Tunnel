package com.barkatunnel.app.account

import android.content.Context
import com.barkatunnel.app.device.DeviceIdentity

class AuthenticatedAccessContextFactory(
    private val context: Context,
    private val sessionStore: AccountSessionStore
) {

    fun create(): AuthenticatedAccessContext? {
        val session = sessionStore.get() ?: return null

        return AuthenticatedAccessContext(
            accountId = session.accountId,
            token = session.token,
            deviceId = DeviceIdentity.getDeviceId(context)
        )
    }
}
