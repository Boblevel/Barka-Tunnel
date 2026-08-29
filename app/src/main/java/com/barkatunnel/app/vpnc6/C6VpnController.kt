package com.barkatunnel.app.vpnc6

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.barkatunnel.app.vpnprofile.VpnProfile
import java.util.UUID
import java.util.concurrent.TimeUnit

class C6VpnController(context: Context) {
    private val appContext = context.applicationContext

    fun connect(profile: VpnProfile): C6VpnResult {
        if (VpnService.prepare(appContext) != null) {
            return C6VpnResult.Error("Permission VPN Android requise.")
        }

        val requestId = UUID.randomUUID().toString()
        val future = C6VpnRuntime.register(requestId)
        val intent = Intent(appContext, BarkaVpnService::class.java).apply {
            action = BarkaVpnService.ACTION_CONNECT
            putExtra(BarkaVpnService.EXTRA_REQUEST_ID, requestId)
            putExtra(BarkaVpnService.EXTRA_PROFILE_ID, profile.networkId)
            putExtra(BarkaVpnService.EXTRA_PROFILE_NAME, profile.displayName)
            putExtra(BarkaVpnService.EXTRA_PROTOCOL, profile.protocol.name)
            putExtra(BarkaVpnService.EXTRA_CONFIG_JSON, profile.configJson)
        }

        return try {
            ContextCompat.startForegroundService(appContext, intent)
            future.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            C6VpnRuntime.cancel(requestId)
            stopWithoutWaiting()
            C6VpnResult.Error("La connexion VPN n’a pas pu être établie dans le délai prévu.")
        }
    }

    fun disconnect(): C6VpnResult {
        val requestId = UUID.randomUUID().toString()
        val future = C6VpnRuntime.register(requestId)
        val intent = Intent(appContext, BarkaVpnService::class.java).apply {
            action = BarkaVpnService.ACTION_DISCONNECT
            putExtra(BarkaVpnService.EXTRA_REQUEST_ID, requestId)
        }

        return try {
            ContextCompat.startForegroundService(appContext, intent)
            future.get(DISCONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            C6VpnRuntime.cancel(requestId)
            C6VpnResult.Error("Délai dépassé pendant la déconnexion VPN.")
        }
    }

    private fun stopWithoutWaiting() {
        runCatching {
            appContext.startService(
                Intent(appContext, BarkaVpnService::class.java).apply {
                    action = BarkaVpnService.ACTION_DISCONNECT
                }
            )
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 180L
        private const val DISCONNECT_TIMEOUT_SECONDS = 20L
    }
}
