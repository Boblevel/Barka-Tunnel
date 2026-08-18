package com.barkatunnel.app.vpn

import com.wireguard.android.backend.Tunnel

class BarkaTunnel(
    private val tunnelName: String = "barka"
) : Tunnel {

    @Volatile
    var state: Tunnel.State = Tunnel.State.DOWN
        private set

    override fun getName(): String {
        return tunnelName
    }

    override fun onStateChange(newState: Tunnel.State) {
        state = newState
    }
}
