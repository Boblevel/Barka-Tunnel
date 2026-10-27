package com.barkatunnel.app.tools

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.vpnc6.BarkaVpnService
import java.net.InetAddress
import java.security.SecureRandom

class VpnShareService : Service() {
    data class State(val address: String, val port: Int, val password: String)
    private var proxy: VpnShareProxy? = null
    private val handler = Handler(Looper.getMainLooper())
    private var socksPort: Int? = null
    private var epoch = 0L
    private fun vpnAlive() = socksPort != null && BarkaVpnService.connectedSocksPort() == socksPort &&
        BarkaVpnService.connectionSnapshot().connectedAtElapsedMs == epoch
    private val monitor = object : Runnable {
        override fun run() {
            if (!vpnAlive()) { stopSelf(); return }
            handler.postDelayed(this, 1000L)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || intent == null) { stopSelf(); return START_NOT_STICKY }
        if (state != null) return START_NOT_STICKY
        error = null
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.vpn_share_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 760, Intent(this, VpnToolsActivity::class.java).putExtra("share", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 761, Intent(this, VpnShareService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(760, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_vpn_share)
            .setContentTitle(getString(R.string.vpn_share_title)).setContentText(getString(R.string.share_background_active))
            .setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.tools_stop), stop).build())
        try {
            socksPort = BarkaVpnService.connectedSocksPort()
            epoch = BarkaVpnService.connectionSnapshot().connectedAtElapsedMs
            check(vpnAlive())
            val address = InetAddress.getByName(requireNotNull(intent.getStringExtra("address")))
            val password = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val auth = android.util.Base64.encodeToString("barka:$password".toByteArray(), android.util.Base64.NO_WRAP)
            proxy = VpnShareProxy(address, socksPort!!, "Basic $auth", ::vpnAlive).also { it.start() }
            state = State(address.hostAddress!!, proxy!!.port, password)
            handler.post(monitor)
        } catch (_: Exception) { error = R.string.tools_share_failed; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); proxy?.close(); proxy = null; state = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else { @Suppress("DEPRECATION") stopForeground(true) }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "barka_vpn_share"
        const val STOP = "com.barkatunnel.app.STOP_SHARE"
        @Volatile var state: State? = null; private set
        @Volatile var error: Int? = null; private set
    }
}
