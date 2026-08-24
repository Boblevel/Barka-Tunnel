package com.barkatunnel.app.vpnc6

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import com.barkatunnel.app.vpnprofile.VpnProfileConfigParser
import com.barkatunnel.app.vpnprofile.VpnProfileProtocol
import java.util.concurrent.Executors

class BarkaVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var engine: C6ProtocolEngine? = null
    @Volatile private var connected = false
    private var tun2SocksRunner: Tun2SocksRunner? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)

        when (action) {
            ACTION_CONNECT -> {
                startForeground(NOTIFICATION_ID, notification("Connexion VPN…"))
                worker.execute { connect(intent, requestId) }
            }
            ACTION_DISCONNECT -> {
                startForeground(NOTIFICATION_ID, notification("Déconnexion VPN…"))
                worker.execute { disconnect(requestId) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        worker.execute { disconnect(null) }
        super.onRevoke()
    }

    override fun onDestroy() {
        if (!stopping) {
            runCatching { tun2SocksRunner?.stop() }
            runCatching { engine?.stop() }
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun connect(intent: Intent, requestId: String?) {
        if (connected) {
            C6VpnRuntime.complete(requestId, C6VpnResult.Error("Un tunnel VPN est déjà actif."))
            return
        }

        try {
            val protocol = VpnProfileProtocol.fromServer(
                intent.getStringExtra(EXTRA_PROTOCOL).orEmpty()
            ) ?: throw IllegalStateException("Protocole VPN C6 invalide.")
            val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
            val profileConfig = VpnProfileConfigParser.parse(protocol, configJson)

            AppLogStore.add(this, "C6 • profil récupéré • protocole ${protocol.name}.")
            val protocolEngine = createEngine(protocol, profileConfig)
            engine = protocolEngine

            AppLogStore.add(this, "C6 • démarrage du moteur ${protocol.name}.")
            protocolEngine.start()

            if (!SocksProbe.connectThrough("127.0.0.1", socksPort(protocol))) {
                throw IllegalStateException("Le tunnel ${protocol.name} n’a pas validé le passage TCP réel.")
            }
            AppLogStore.add(this, "C6 • transport ${protocol.name} validé.")

            val dns = when (profileConfig) {
                is VpnProfileConfig.SlowDns -> profileConfig.dns
                else -> "1.1.1.1"
            }
            val descriptor = Builder()
                .setSession("Barka Tunnel")
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(dns)
                .addDisallowedApplication(packageName)
                .establish()
                ?: throw IllegalStateException("Android n’a pas créé l’interface VPN.")

            val udpgw = when (profileConfig) {
                is VpnProfileConfig.UdpCustom -> "${profileConfig.udpGwHost}:${profileConfig.udpGwPort}"
                else -> DEFAULT_UDPGW
            }

            AppLogStore.add(this, "C6 • interface TUN créée, démarrage tun2socks.")
            tun2SocksRunner = Tun2SocksRunner(this).also { runner ->
                runner.start(
                    vpnDescriptor = descriptor,
                    mtu = VPN_MTU,
                    vpnAddress = VPN_ADDRESS,
                    netmask = VPN_NETMASK,
                    socksAddress = protocolEngine.socksAddress,
                    udpgwAddress = udpgw
                )
            }

            connected = true
            updateNotification("VPN connecté • ${protocol.name}")
            AppLogStore.add(this, "C6 • CONNECTÉ • ${protocol.name}.")
            C6VpnRuntime.complete(requestId, C6VpnResult.Connected(protocol.name))
        } catch (e: Exception) {
            val message = sanitizeError(e.message ?: "Échec du tunnel C6.")
            AppLogStore.add(this, "C6 • ERREUR • $message")
            stopTunnel()
            C6VpnRuntime.complete(requestId, C6VpnResult.Error(message))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun disconnect(requestId: String?) {
        stopping = true
        AppLogStore.add(this, "C6 • déconnexion demandée.")
        stopTunnel()
        C6VpnRuntime.complete(requestId, C6VpnResult.Disconnected)
        AppLogStore.add(this, "C6 • VPN déconnecté proprement.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTunnel() {
        runCatching { tun2SocksRunner?.stop() }
        tun2SocksRunner = null
        runCatching { engine?.stop() }
        engine = null
        connected = false
    }

    private fun createEngine(
        protocol: VpnProfileProtocol,
        config: VpnProfileConfig
    ): C6ProtocolEngine = when (protocol) {
        VpnProfileProtocol.VLESS -> XrayVlessEngine(
            context = this,
            config = config as VpnProfileConfig.Vless,
            socksPort = SOCKS_VLESS
        )
        VpnProfileProtocol.SLOWDNS -> SlowDnsEngine(
            context = this,
            config = config as VpnProfileConfig.SlowDns,
            socksPort = SOCKS_SLOWDNS
        )
        VpnProfileProtocol.UDP -> UdpSshEngine(
            config = config as VpnProfileConfig.UdpCustom,
            socksPort = SOCKS_UDP
        )
    }

    private fun socksPort(protocol: VpnProfileProtocol): Int = when (protocol) {
        VpnProfileProtocol.VLESS -> SOCKS_VLESS
        VpnProfileProtocol.SLOWDNS -> SOCKS_SLOWDNS
        VpnProfileProtocol.UDP -> SOCKS_UDP
    }

    private fun notification(text: String): android.app.Notification {
        createNotificationChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_barka_logo)
            .setContentTitle("Barka Tunnel")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Connexion VPN",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun sanitizeError(value: String): String {
        val clean = value.replace(Regex("(?i)(password|uuid|pubkey|public[_ -]?key)\\s*[:=]\\s*\\S+"), "$1=[masqué]")
        return clean.take(220)
    }

    companion object {
        const val ACTION_CONNECT = "com.barkatunnel.app.C6_CONNECT"
        const val ACTION_DISCONNECT = "com.barkatunnel.app.C6_DISCONNECT"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_CONFIG_JSON = "config_json"

        private const val CHANNEL_ID = "barka_vpn"
        private const val NOTIFICATION_ID = 6001
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "10.10.0.2"
        private const val VPN_NETMASK = "255.255.255.0"
        private const val DEFAULT_UDPGW = "127.0.0.1:7300"
        private const val SOCKS_VLESS = 10808
        private const val SOCKS_SLOWDNS = 10809
        private const val SOCKS_UDP = 10810
    }
}
