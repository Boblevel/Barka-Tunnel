package com.barkatunnel.app.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

class WireGuardManager(context: Context) {

    private val backend = GoBackend(context.applicationContext)

    val tunnel = BarkaTunnel()

    fun connect(config: Config): Tunnel.State {
        return backend.setState(
            tunnel,
            Tunnel.State.UP,
            config
        )
    }

    fun disconnect(): Tunnel.State {
        return backend.setState(
            tunnel,
            Tunnel.State.DOWN,
            null
        )
    }

    fun getState(): Tunnel.State {
        return backend.getState(tunnel)
    }
}
