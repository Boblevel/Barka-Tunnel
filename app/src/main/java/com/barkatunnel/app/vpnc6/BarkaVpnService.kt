package com.barkatunnel.app.vpnc6

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import com.barkatunnel.app.vpnprofile.VpnProfileConfigParser
import com.barkatunnel.app.vpnprofile.VpnProfileProtocol
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class BarkaVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val keepAliveExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var engine: C6ProtocolEngine? = null
    @Volatile private var connected = false
    private var tun2SocksRunner: Tun2SocksRunner? = null
    private var keepAliveFuture: ScheduledFuture<*>? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)

        when (action) {
            ACTION_CONNECT -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(this, getString(R.string.notification_connecting))
                )
                worker.execute { connect(intent, requestId) }
            }
            ACTION_DISCONNECT -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(this, getString(R.string.notification_disconnecting))
                )
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
        keepAliveFuture?.cancel(true)
        keepAliveFuture = null
        keepAliveExecutor.shutdownNow()
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

            val protocolEngine = createEngine(protocol, profileConfig)
            engine = protocolEngine

            protocolEngine.start()

            if (!waitForProxyReady(socksPort(protocol))) {
                throw IllegalStateException("Le tunnel ${protocol.name} n’a pas validé le passage TCP réel.")
            }

            val dns = when (profileConfig) {
                is VpnProfileConfig.SlowDns -> profileConfig.dns
                else -> "1.1.1.1"
            }
            val descriptor = Builder()
                .setSession("Barka Tunnel")
                .setMtu(VPN_MTU)
                .addAddress(VPN_INTERFACE_ADDRESS, 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(dns)
                .addDisallowedApplication(packageName)
                .establish()
                ?: throw IllegalStateException("Android n’a pas créé l’interface VPN.")

            val udpgw = when (profileConfig) {
                is VpnProfileConfig.UdpCustom -> "${profileConfig.udpGwHost}:${profileConfig.udpGwPort}"
                is VpnProfileConfig.SlowDns -> DEFAULT_UDPGW
                is VpnProfileConfig.Vless -> null
            }

            tun2SocksRunner = Tun2SocksRunner(this).also { runner ->
                runner.start(
                    vpnDescriptor = descriptor,
                    mtu = VPN_MTU,
                    vpnAddress = TUN2SOCKS_ROUTER_ADDRESS,
                    netmask = VPN_NETMASK,
                    socksAddress = protocolEngine.socksAddress,
                    udpgwAddress = udpgw,
                    forwardUdpThroughSocks = protocol == VpnProfileProtocol.VLESS
                )
            }

            connected = true
            startAutoPing(protocol)
            updateNotification(getString(R.string.notification_connected))
            C6VpnRuntime.complete(requestId, C6VpnResult.Connected(protocol.name))
        } catch (e: Exception) {
            val technicalMessage = sanitizeError(e.message ?: "Échec interne de la connexion.")
            Log.e(TAG, "Connection failure: $technicalMessage", e)
            AppLogStore.add(this, "Connexion refusée.")
            stopTunnel()
            C6VpnRuntime.complete(
                requestId,
                C6VpnResult.Error(getString(R.string.connection_pending_help))
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun disconnect(requestId: String?) {
        stopping = true
        stopTunnel()
        C6VpnRuntime.complete(requestId, C6VpnResult.Disconnected)
        AppLogStore.add(this, "VPN déconnecté.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTunnel() {
        keepAliveFuture?.cancel(true)
        keepAliveFuture = null
        runCatching { tun2SocksRunner?.stop() }
        tun2SocksRunner = null
        runCatching { engine?.stop() }
        engine = null
        connected = false
    }

    private fun waitForProxyReady(port: Int): Boolean {
        repeat(20) {
            if (SocksProbe.connectThrough("127.0.0.1", port)) return true
            Thread.sleep(1000)
        }
        return false
    }

    private fun startAutoPing(protocol: VpnProfileProtocol) {
        keepAliveFuture?.cancel(true)
        val port = socksPort(protocol)
        keepAliveFuture = keepAliveExecutor.scheduleWithFixedDelay(
            {
                if (connected) {
                    val enabled = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                        .getBoolean(KEY_AUTO_PING, false)
                    if (enabled) {
                        // Le contrôle passe volontairement par le proxy SOCKS
                        // du protocole actif. Il garde donc réellement
                        // SlowDNS/SSH/VLESS en activité au lieu d'envoyer un
                        // ping hors du VPN.
                        val start = System.currentTimeMillis()
                        val ok = SocksProbe.connectThrough(
                            proxyHost = "127.0.0.1",
                            proxyPort = port,
                            destinationHost = AUTO_PING_HOST,
                            destinationPort = AUTO_PING_PORT,
                            timeoutMs = AUTO_PING_TIMEOUT_MS
                        )
                        val ping = System.currentTimeMillis() - start
                        AppLogStore.add(this, "Ping : ${ping} ms ${if (ok) "OK" else "Échec"}")
                    }
                }
            },
            AUTO_PING_INITIAL_DELAY_SECONDS,
            AUTO_PING_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
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

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(this, text))
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

        fun showConnectingNotification(context: Context) {
            val appContext = context.applicationContext
            appContext.getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(
                    appContext,
                    appContext.getString(R.string.notification_connecting)
                )
            )
        }

        fun showWaitingNotification(context: Context) {
            val appContext = context.applicationContext
            appContext.getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(
                    appContext,
                    appContext.getString(R.string.notification_waiting)
                )
            )
        }

        fun cancelConnectingNotification(context: Context) {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                .cancel(NOTIFICATION_ID)
        }

        private fun buildNotification(
            context: Context,
            text: String
        ): android.app.Notification {
            createNotificationChannel(context)
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_barka)
                .setContentTitle("Barka Tunnel")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(ContextCompat.getColor(context, R.color.barka_blue))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        private const val TAG = "BarkaVpnService"
        private const val CHANNEL_ID = "barka_vpn_status_v2"
        private const val NOTIFICATION_ID = 6001
        private const val VPN_MTU = 1500
        private const val VPN_INTERFACE_ADDRESS = "10.10.0.1"
        private const val TUN2SOCKS_ROUTER_ADDRESS = "10.10.0.2"
        private const val VPN_NETMASK = "255.255.255.0"
        private const val DEFAULT_UDPGW = "127.0.0.1:7300"
        private const val SOCKS_VLESS = 10808
        private const val SOCKS_SLOWDNS = 10809
        private const val SOCKS_UDP = 10810
        private const val SETTINGS_PREFS = "barka_settings"
        private const val KEY_AUTO_PING = "auto_ping"
        private const val AUTO_PING_HOST = "1.1.1.1"
        private const val AUTO_PING_PORT = 443
        private const val AUTO_PING_TIMEOUT_MS = 4_000
        private const val AUTO_PING_INITIAL_DELAY_SECONDS = 10L
        private const val AUTO_PING_INTERVAL_SECONDS = 20L
    }
}
