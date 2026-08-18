package com.barkatunnel.app.vpn

import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.GoBackend

object VpnPermissionHelper {

    fun prepare(context: Context): Intent? {
        return GoBackend.VpnService.prepare(context)
    }
}
