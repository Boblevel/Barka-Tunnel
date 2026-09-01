package com.barkatunnel.app.vpnc6

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import com.barkatunnel.app.vpnprofile.VpnProfileConfigParser
import com.barkatunnel.app.vpnprofile.VpnProfileProtocol
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BarkaVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val keepAliveExecutor = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var engine: C6ProtocolEngine? = null
    @Volatile private var connected = false
    private var tun2SocksRunner: Tun2SocksRunner? = null
    private var keepAliveFuture: ScheduledFuture<*>? = null
    private val tunnelLock = Any()
    private val operationGeneration = AtomicLong(0L)
    private val healthRecoveryScheduled = AtomicBoolean(false)
    @Volatile private var vpnDescriptor: ParcelFileDescriptor? = null
    @Volatile private var stopping = false
    @Volatile private var activeSession: ActiveSession? = null
    @Volatile private var activeConnectRequestId: String? = null
    @Volatile private var connectFuture: Future<*>? = null
    @Volatile private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)

        when (action) {
            ACTION_CONNECT -> {
                if (
                    connectFuture?.isDone == false ||
                    connected ||
                    runtimeState == RuntimeConnectionState.CONNECTING ||
                    runtimeState == RuntimeConnectionState.DISCONNECTING
                ) {
                    C6VpnRuntime.complete(
                        requestId,
                        C6VpnResult.Error("Une opération VPN est déjà en cours.")
                    )
                    return START_NOT_STICKY
                }
                val connectGeneration = operationGeneration.incrementAndGet()
                stopping = false
                activeSession = null
                healthRecoveryScheduled.set(false)
                activeConnectRequestId = requestId
                updateRuntimeState(
                    RuntimeConnectionState.CONNECTING,
                    intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty(),
                    0L
                )
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(this, getString(R.string.notification_connecting))
                )
                connectFuture = worker.submit {
                    try {
                        connect(intent, requestId, connectGeneration)
                    } finally {
                        connectFuture = null
                    }
                }
            }
            ACTION_DISCONNECT -> {
                // Chaque démarrage via startForegroundService doit publier sa
                // notification avant tout retour, même si une annulation de
                // connexion a déjà remis l'état à DISCONNECTED entre-temps.
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(this, getString(R.string.notification_disconnecting))
                )
                if (runtimeState == RuntimeConnectionState.DISCONNECTING) {
                    C6VpnRuntime.complete(requestId, C6VpnResult.Disconnected)
                    return START_NOT_STICKY
                }
                val disconnectGeneration = operationGeneration.incrementAndGet()
                stopping = true
                activeSession = null
                healthRecoveryScheduled.set(false)
                AppLogStore.add(this, "Déconnexion en cours.")
                updateRuntimeState(
                    RuntimeConnectionState.DISCONNECTING,
                    runtimeProfileName,
                    runtimeConnectedAtElapsedMs
                )
                connectFuture?.cancel(true)
                worker.execute { disconnect(requestId, disconnectGeneration) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        val disconnectGeneration = operationGeneration.incrementAndGet()
        stopping = true
        activeSession = null
        healthRecoveryScheduled.set(false)
        AppLogStore.add(this, "Déconnexion en cours.")
        updateRuntimeState(
            RuntimeConnectionState.DISCONNECTING,
            runtimeProfileName,
            runtimeConnectedAtElapsedMs
        )
        connectFuture?.cancel(true)
        worker.execute { disconnect(null, disconnectGeneration) }
        super.onRevoke()
    }

    override fun onDestroy() {
        operationGeneration.incrementAndGet()
        stopping = true
        activeSession = null
        healthRecoveryScheduled.set(false)
        connectFuture?.cancel(true)
        connectFuture = null
        mainHandler.removeCallbacksAndMessages(null)
        stopTunnel()
        keepAliveFuture?.cancel(true)
        keepAliveFuture = null
        keepAliveExecutor.shutdownNow()
        worker.shutdownNow()
        C6VpnRuntime.complete(activeConnectRequestId, C6VpnResult.Disconnected)
        activeConnectRequestId = null
        updateRuntimeState(RuntimeConnectionState.DISCONNECTED, null, 0L)
        super.onDestroy()
    }

    private fun connect(
        intent: Intent,
        requestId: String?,
        connectGeneration: Long
    ) {
        if (connected) {
            completeConnectRequest(
                requestId,
                C6VpnResult.Error("Un tunnel VPN est déjà actif.")
            )
            return
        }

        try {
            val protocol = VpnProfileProtocol.fromServer(
                intent.getStringExtra(EXTRA_PROTOCOL).orEmpty()
            ) ?: throw IllegalStateException("Protocole VPN C6 invalide.")
            val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
            val profileConfig = VpnProfileConfigParser.parse(protocol, configJson)
            val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
            AppLogStore.add(this, "Diagnostic VPN • profil ${protocol.name} chargé.")

            if (!isConnectOperationActive(connectGeneration)) {
                completeConnectRequest(requestId, C6VpnResult.Disconnected)
                return
            }

            var lastFailure: Throwable? = null
            for (attempt in 1..MAX_CONNECTION_ATTEMPTS) {
                if (!isConnectOperationActive(connectGeneration)) {
                    completeConnectRequest(requestId, C6VpnResult.Disconnected)
                    return
                }

                try {
                    stopTunnel()
                    if (!isConnectOperationActive(connectGeneration)) {
                        completeConnectRequest(requestId, C6VpnResult.Disconnected)
                        return
                    }

                    if (attempt > 1) {
                        AppLogStore.add(
                            this,
                            "Tentative de connexion automatique${profileName.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()}."
                        )
                        updateNotification(getString(R.string.notification_connecting))
                    }

                    connectOnce(protocol, profileConfig, connectGeneration)
                    if (!isConnectOperationActive(connectGeneration)) {
                        stopTunnel()
                        completeConnectRequest(requestId, C6VpnResult.Disconnected)
                        return
                    }

                    connected = true
                    activeSession = ActiveSession(protocol, profileConfig, profileName)
                    updateRuntimeState(
                        RuntimeConnectionState.CONNECTED,
                        profileName,
                        SystemClock.elapsedRealtime()
                    )
                    startConnectionMonitor(protocol)
                    updateNotification(getString(R.string.notification_connected))
                    AppLogStore.add(
                        this,
                        "VPN connecté${profileName.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()}."
                    )
                    completeConnectRequest(
                        requestId,
                        C6VpnResult.Connected(protocol.name)
                    )
                    return
                } catch (error: Throwable) {
                    if (error !is Exception && error !is LinkageError) throw error
                    val cancelled = !isConnectOperationActive(connectGeneration) ||
                        error is InterruptedException ||
                        Thread.currentThread().isInterrupted
                    val technicalMessage = sanitizeError(
                        error.message ?: "Échec interne de la connexion."
                    )
                    Log.w(TAG, "Connection attempt $attempt failed: $technicalMessage", error)
                    AppLogStore.add(
                        this,
                        "Diagnostic VPN • tentative $attempt • $technicalMessage"
                    )
                    stopTunnel()

                    if (cancelled) {
                        completeConnectRequest(requestId, C6VpnResult.Disconnected)
                        return
                    }

                    lastFailure = error
                    if (attempt < MAX_CONNECTION_ATTEMPTS) {
                        try {
                            Thread.sleep(CONNECTION_RETRY_DELAY_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            completeConnectRequest(requestId, C6VpnResult.Disconnected)
                            return
                        }
                    }
                }
            }

            val finalMessage = sanitizeError(
                lastFailure?.message ?: "Échec interne de la connexion."
            )
            Log.w(TAG, "Connection failed after retries: $finalMessage")
            AppLogStore.add(this, "Connexion refusée.")
            activeSession = null
            updateRuntimeState(RuntimeConnectionState.DISCONNECTED, null, 0L)
            finishServiceIfIdle(connectGeneration)
            completeConnectRequest(
                requestId,
                C6VpnResult.Error(getString(R.string.connection_failed_help))
            )
            return
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            val technicalMessage = sanitizeError(error.message ?: "Échec interne de la connexion.")
            Log.e(TAG, "Connection failure: $technicalMessage", error)
            AppLogStore.add(this, "Connexion refusée.")
            stopTunnel()
            activeSession = null
            updateRuntimeState(RuntimeConnectionState.DISCONNECTED, null, 0L)
            finishServiceIfIdle(connectGeneration)
            completeConnectRequest(
                requestId,
                C6VpnResult.Error(getString(R.string.connection_failed_help))
            )
        }
    }

    private fun connectOnce(
        protocol: VpnProfileProtocol,
        profileConfig: VpnProfileConfig,
        connectGeneration: Long
    ) {
        val localSocksPort = socksPort(protocol)
        if (
            !waitUntilLocalPortClosed(
                port = localSocksPort,
                timeoutMs = LOCAL_PROXY_RELEASE_TIMEOUT_MS
            ) { isConnectOperationActive(connectGeneration) }
        ) {
            if (!isConnectOperationActive(connectGeneration)) {
                throw InterruptedException("Connexion annulée.")
            }
            throw IllegalStateException(
                "Le proxy local de la session précédente n’est pas encore libéré."
            )
        }

        val protocolEngine = createEngine(protocol, profileConfig, connectGeneration)
        engine = protocolEngine
        AppLogStore.add(this, "Diagnostic VPN • démarrage moteur ${protocol.name}.")
        protocolEngine.start()
        if (!isConnectOperationActive(connectGeneration)) {
            throw InterruptedException("Connexion annulée.")
        }
        AppLogStore.add(this, "Diagnostic VPN • moteur ${protocol.name} actif.")

        if (
            !PortWaiter.waitUntilOpen(
                "127.0.0.1",
                localSocksPort,
                LOCAL_PROXY_READY_TIMEOUT_MS
            ) { isConnectOperationActive(connectGeneration) }
        ) {
            if (!isConnectOperationActive(connectGeneration)) {
                throw InterruptedException("Connexion annulée.")
            }
            throw IllegalStateException("Le tunnel ${protocol.name} n’a pas ouvert son proxy local.")
        }
        if (!isConnectOperationActive(connectGeneration)) {
            throw InterruptedException("Connexion annulée.")
        }
        AppLogStore.add(this, "Diagnostic VPN • proxy SOCKS ${protocol.name} prêt.")

        if (!protocolEngine.isRunning()) {
            throw IllegalStateException("Le moteur ${protocol.name} s’est arrêté prématurément.")
        }
        val probeOk = SocksProbe.hasUsableInternet(
            proxyHost = "127.0.0.1",
            proxyPort = localSocksPort,
            timeoutMs = DIAGNOSTIC_SOCKS_TIMEOUT_MS
        )
        val diagnosticNetwork = when (protocol) {
            VpnProfileProtocol.VLESS -> "ORANGE"
            VpnProfileProtocol.SLOWDNS -> "MOOV"
            VpnProfileProtocol.UDP -> "TELECEL"
        }
        AppLogStore.add(
            this,
            "Diagnostic $diagnosticNetwork • trafic réel via SOCKS • ${if (probeOk) "OK" else "ÉCHEC"}."
        )
        if (!probeOk || !protocolEngine.isRunning()) {
            throw IllegalStateException(
                "Le proxy ${protocol.name} n’achemine aucune donnée Internet."
            )
        }

        val dns = when (profileConfig) {
            is VpnProfileConfig.SlowDns -> profileConfig.dns
            else -> "1.1.1.1"
        }
        if (!isConnectOperationActive(connectGeneration)) {
            throw InterruptedException("Connexion annulée.")
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
        synchronized(tunnelLock) {
            if (!isConnectOperationActive(connectGeneration)) {
                runCatching { descriptor.close() }
                throw InterruptedException("Connexion annulée.")
            }
            vpnDescriptor = descriptor
        }
        AppLogStore.add(this, "Diagnostic VPN • interface TUN Android créée.")

        val udpgw = when (profileConfig) {
            is VpnProfileConfig.UdpCustom -> "${profileConfig.udpGwHost}:${profileConfig.udpGwPort}"
            is VpnProfileConfig.SlowDns -> DEFAULT_UDPGW
            is VpnProfileConfig.Vless -> null
        }

        AppLogStore.add(
            this,
            "Diagnostic VPN • démarrage tun2socks • UDP=${if (protocol == VpnProfileProtocol.VLESS) "SOCKS5" else if (udpgw != null) "UDPGW" else "OFF"}."
        )
        synchronized(tunnelLock) {
            if (!isConnectOperationActive(connectGeneration)) {
                runCatching { descriptor.close() }
                vpnDescriptor = null
                throw InterruptedException("Connexion annulée.")
            }

            val runner = Tun2SocksRunner(this)
            tun2SocksRunner = runner
            runner.start(
                vpnDescriptor = descriptor,
                mtu = VPN_MTU,
                vpnAddress = TUN2SOCKS_ROUTER_ADDRESS,
                netmask = VPN_NETMASK,
                socksAddress = protocolEngine.socksAddress,
                udpgwAddress = udpgw,
                forwardUdpThroughSocks = protocol == VpnProfileProtocol.VLESS
            )

            if (!isConnectOperationActive(connectGeneration)) {
                throw InterruptedException("Connexion annulée.")
            }
            if (!runner.isRunning()) {
                throw IllegalStateException("tun2socks ne transporte pas le trafic VPN.")
            }
        }
        AppLogStore.add(this, "Diagnostic VPN • tun2socks actif.")
    }

    private fun disconnect(requestId: String?, disconnectGeneration: Long) {
        if (operationGeneration.get() != disconnectGeneration) {
            C6VpnRuntime.complete(requestId, C6VpnResult.Disconnected)
            return
        }
        stopping = true
        stopTunnel()
        if (operationGeneration.get() != disconnectGeneration) {
            C6VpnRuntime.complete(requestId, C6VpnResult.Disconnected)
            return
        }
        val pendingConnectRequestId = activeConnectRequestId
        activeConnectRequestId = null
        activeSession = null
        healthRecoveryScheduled.set(false)
        updateRuntimeState(RuntimeConnectionState.DISCONNECTED, null, 0L)
        AppLogStore.add(this, "VPN déconnecté.")
        finishServiceIfIdle(disconnectGeneration)
        C6VpnRuntime.complete(pendingConnectRequestId, C6VpnResult.Disconnected)
        C6VpnRuntime.complete(requestId, C6VpnResult.Disconnected)
    }

    private fun stopTunnel() {
        keepAliveFuture?.cancel(true)
        keepAliveFuture = null

        val descriptor: ParcelFileDescriptor?
        val runner: Tun2SocksRunner?
        val activeEngine: C6ProtocolEngine?
        synchronized(tunnelLock) {
            descriptor = vpnDescriptor
            vpnDescriptor = null
            runner = tun2SocksRunner
            tun2SocksRunner = null
            activeEngine = engine
            engine = null
            connected = false
        }

        // Arrêter d'abord tun2socks pendant que le descripteur TUN est encore valide.
        // Fermer le TUN avant l'arrêt natif peut faire tomber brutalement le pont
        // TUN -> SOCKS et laisser les tentatives suivantes dans un état incohérent.
        runCatching { runner?.stop() }
            .onFailure { Log.w(TAG, "tun2socks shutdown incomplete", it) }
        runCatching { descriptor?.close() }
        runCatching { activeEngine?.stop() }
            .onFailure { Log.w(TAG, "Protocol engine shutdown incomplete", it) }

        val releasedPort = activeEngine?.socksAddress
            ?.substringAfterLast(':')
            ?.toIntOrNull()
        if (
            releasedPort != null &&
            !waitUntilLocalPortClosed(
                port = releasedPort,
                timeoutMs = LOCAL_PROXY_RELEASE_TIMEOUT_MS
            ) { true }
        ) {
            Log.w(TAG, "Local proxy port $releasedPort is still open after shutdown")
        }
    }

    private fun waitUntilLocalPortClosed(
        port: Int,
        timeoutMs: Long,
        shouldContinue: () -> Boolean
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted || !shouldContinue()) {
                return false
            }
            if (!isLocalPortOpen(port)) {
                return true
            }
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !isLocalPortOpen(port)
    }

    private fun isLocalPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 150)
        }
        true
    }.getOrDefault(false)

    private fun isConnectOperationActive(connectGeneration: Long): Boolean =
        !stopping && operationGeneration.get() == connectGeneration

    private fun completeConnectRequest(requestId: String?, result: C6VpnResult) {
        if (activeConnectRequestId == requestId) {
            activeConnectRequestId = null
        }
        C6VpnRuntime.complete(requestId, result)
    }

    private fun finishServiceIfIdle(expectedGeneration: Long) {
        mainHandler.post {
            if (
                operationGeneration.get() != expectedGeneration ||
                runtimeState != RuntimeConnectionState.DISCONNECTED ||
                connected ||
                activeConnectRequestId != null
            ) {
                return@post
            }

            val startId = latestStartId
            if (startId > 0) {
                if (stopSelfResult(startId)) {
                    stopForegroundCompat()
                }
            } else {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun startConnectionMonitor(protocol: VpnProfileProtocol) {
        keepAliveFuture?.cancel(true)
        val port = socksPort(protocol)
        var consecutiveFailures = 0
        keepAliveFuture = keepAliveExecutor.scheduleWithFixedDelay(
            {
                if (connected && !stopping) {
                    val runnerHealthy = synchronized(tunnelLock) {
                        vpnDescriptor != null &&
                            engine?.isRunning() == true &&
                            tun2SocksRunner?.isRunning() == true
                    }
                    if (!runnerHealthy) {
                        scheduleHealthRecovery("Le moteur du tunnel s’est arrêté.")
                    } else {
                        val enabled = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                            .getBoolean(KEY_AUTO_PING, false)
                        // Ce contrôle valide le relais SOCKS. Le processus
                        // tun2socks est contrôlé séparément ci-dessus, car
                        // l'application est volontairement exclue du TUN.
                        val startNs = System.nanoTime()
                        val ok = SocksProbe.hasUsableInternet(
                            proxyHost = "127.0.0.1",
                            proxyPort = port,
                            timeoutMs = AUTO_PING_TIMEOUT_MS
                        )
                        val latencyMs = (System.nanoTime() - startNs) / 1_000_000L

                        if (ok) {
                            consecutiveFailures = 0
                        } else {
                            consecutiveFailures += 1
                        }

                        if (enabled) {
                            AppLogStore.add(
                                this,
                                "Ping : ${latencyMs} ms ${if (ok) "OK" else "Échec"}"
                            )
                        }

                        if (!ok && consecutiveFailures >= HEALTH_FAILURE_LIMIT) {
                            scheduleHealthRecovery(
                                "Le proxy VPN ne transmet plus les données."
                            )
                        }
                    }
                }
            },
            AUTO_PING_INITIAL_DELAY_SECONDS,
            AUTO_PING_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    private fun scheduleHealthRecovery(reason: String) {
        if (!connected || stopping) return
        val session = activeSession ?: return
        if (!healthRecoveryScheduled.compareAndSet(false, true)) return

        val recoveryGeneration = operationGeneration.incrementAndGet()
        val connectedAtElapsedMs = runtimeConnectedAtElapsedMs
        connected = false
        updateRuntimeState(
            RuntimeConnectionState.CONNECTING,
            session.profileName,
            connectedAtElapsedMs
        )
        AppLogStore.add(this, "Rétablissement automatique de la connexion VPN.")
        updateNotification(getString(R.string.notification_connecting))

        connectFuture = worker.submit {
            try {
                recoverConnection(
                    session = session,
                    recoveryGeneration = recoveryGeneration,
                    connectedAtElapsedMs = connectedAtElapsedMs,
                    reason = reason
                )
            } finally {
                if (!connected) {
                    healthRecoveryScheduled.set(false)
                }
                connectFuture = null
            }
        }
    }

    private fun recoverConnection(
        session: ActiveSession,
        recoveryGeneration: Long,
        connectedAtElapsedMs: Long,
        reason: String
    ) {
        var attempt = 0
        while (
            isConnectOperationActive(recoveryGeneration) &&
            activeSession == session
        ) {
            attempt += 1
            try {
                stopTunnel()
                if (!isConnectOperationActive(recoveryGeneration)) return

                connectOnce(session.protocol, session.config, recoveryGeneration)
                if (!isConnectOperationActive(recoveryGeneration)) {
                    stopTunnel()
                    return
                }

                connected = true
                activeSession = session
                healthRecoveryScheduled.set(false)
                updateRuntimeState(
                    RuntimeConnectionState.CONNECTED,
                    session.profileName,
                    connectedAtElapsedMs.takeIf { it > 0L }
                        ?: SystemClock.elapsedRealtime()
                )
                startConnectionMonitor(session.protocol)
                updateNotification(getString(R.string.notification_connected))
                AppLogStore.add(this, "Connexion VPN rétablie.")
                return
            } catch (error: Throwable) {
                if (error !is Exception && error !is LinkageError) throw error
                stopTunnel()
                if (
                    !isConnectOperationActive(recoveryGeneration) ||
                    error is InterruptedException ||
                    Thread.currentThread().isInterrupted
                ) {
                    return
                }
                Log.w(
                    TAG,
                    "VPN recovery attempt $attempt failed after: $reason",
                    error
                )

                val exponent = minOf((attempt - 1).coerceAtLeast(0), 3)
                val retryDelayMs = minOf(
                    RECOVERY_INITIAL_DELAY_MS * (1L shl exponent),
                    RECOVERY_MAX_DELAY_MS
                )
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun createEngine(
        protocol: VpnProfileProtocol,
        config: VpnProfileConfig,
        connectGeneration: Long
    ): C6ProtocolEngine = when (protocol) {
        VpnProfileProtocol.VLESS -> XrayVlessEngine(
            context = this,
            config = config as VpnProfileConfig.Vless,
            socksPort = SOCKS_VLESS,
            isCancelled = { !isConnectOperationActive(connectGeneration) }
        )
        VpnProfileProtocol.SLOWDNS -> SlowDnsEngine(
            context = this,
            config = config as VpnProfileConfig.SlowDns,
            socksPort = SOCKS_SLOWDNS,
            isCancelled = { !isConnectOperationActive(connectGeneration) }
        )
        VpnProfileProtocol.UDP -> UdpSshEngine(
            context = this,
            config = config as VpnProfileConfig.UdpCustom,
            socksPort = SOCKS_UDP,
            isCancelled = { !isConnectOperationActive(connectGeneration) }
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

    private fun updateRuntimeState(
        state: RuntimeConnectionState,
        profileName: String?,
        connectedAtElapsedMs: Long
    ) {
        runtimeState = state
        runtimeProfileName = profileName?.takeIf { it.isNotBlank() }
        runtimeConnectedAtElapsedMs = connectedAtElapsedMs
    }

    enum class RuntimeConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    data class ConnectionSnapshot(
        val state: RuntimeConnectionState,
        val profileName: String?,
        val connectedAtElapsedMs: Long
    )

    private data class ActiveSession(
        val protocol: VpnProfileProtocol,
        val config: VpnProfileConfig,
        val profileName: String
    )

    companion object {
        @Volatile private var runtimeState = RuntimeConnectionState.DISCONNECTED
        @Volatile private var runtimeProfileName: String? = null
        @Volatile private var runtimeConnectedAtElapsedMs: Long = 0L

        fun connectionSnapshot(): ConnectionSnapshot = ConnectionSnapshot(
            state = runtimeState,
            profileName = runtimeProfileName,
            connectedAtElapsedMs = runtimeConnectedAtElapsedMs
        )
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
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_barka)
                .setContentTitle("Barka Tunnel")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(ContextCompat.getColor(context, R.color.barka_blue))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            if (runtimeState == RuntimeConnectionState.CONNECTED) {
                builder.addAction(
                    R.drawable.ic_power_barka,
                    context.getString(R.string.disconnect),
                    disconnectPendingIntent(context)
                )
            }

            val connectedAtElapsedMs = runtimeConnectedAtElapsedMs
            if (
                runtimeState == RuntimeConnectionState.CONNECTED &&
                connectedAtElapsedMs > 0L
            ) {
                val connectedDurationMs =
                    (SystemClock.elapsedRealtime() - connectedAtElapsedMs).coerceAtLeast(0L)
                builder
                    .setWhen(System.currentTimeMillis() - connectedDurationMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
            } else {
                builder
                    .setShowWhen(false)
                    .setUsesChronometer(false)
            }

            return builder.build()
        }

        private fun disconnectPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, BarkaVpnService::class.java)
                .setAction(ACTION_DISCONNECT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, DISCONNECT_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(context, DISCONNECT_REQUEST_CODE, intent, flags)
            }
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
        private const val DISCONNECT_REQUEST_CODE = 6002
        private const val VPN_MTU = 1500
        private const val VPN_INTERFACE_ADDRESS = "10.10.0.1"
        private const val TUN2SOCKS_ROUTER_ADDRESS = "10.10.0.2"
        private const val VPN_NETMASK = "255.255.255.0"
        private const val DEFAULT_UDPGW = "127.0.0.1:7300"
        private const val SOCKS_VLESS = 10808
        private const val SOCKS_SLOWDNS = 10809
        private const val SOCKS_UDP = 10810
        private const val LOCAL_PROXY_READY_TIMEOUT_MS = 4_000L
        private const val LOCAL_PROXY_RELEASE_TIMEOUT_MS = 4_000L
        private const val MAX_CONNECTION_ATTEMPTS = 1
        private const val CONNECTION_RETRY_DELAY_MS = 1_500L
        private const val DIAGNOSTIC_SOCKS_TIMEOUT_MS = 2_000
        private const val SETTINGS_PREFS = "barka_settings"
        private const val KEY_AUTO_PING = "auto_ping"
        private const val AUTO_PING_TIMEOUT_MS = 2_000
        private const val AUTO_PING_INITIAL_DELAY_SECONDS = 3L
        private const val AUTO_PING_INTERVAL_SECONDS = 12L
        private const val HEALTH_FAILURE_LIMIT = 3
        private const val RECOVERY_INITIAL_DELAY_MS = 2_000L
        private const val RECOVERY_MAX_DELAY_MS = 15_000L
    }
}
