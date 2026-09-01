package com.barkatunnel.app.vpnc6

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import java.util.concurrent.Executors

/**
 * Héberge BadVPN dans un processus privé qui est recréé pour chaque session.
 * Le moteur natif conserve un état global ; l'isolation empêche cet état de
 * contaminer la connexion suivante après une déconnexion.
 */
class Tun2SocksProcessService : Service() {
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val incomingMessenger = Messenger(IncomingHandler())

    @Volatile private var nativeRunner: NativeTun2SocksRunner? = null
    @Volatile private var shuttingDown = false

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        shutdownProcess(null)
        return false
    }

    override fun onDestroy() {
        commandExecutor.shutdownNow()
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                COMMAND_START -> {
                    val replyTo = message.replyTo
                    val config = runCatching { readConfig(message.data) }.getOrElse { error ->
                        sendReply(replyTo, RESPONSE_ERROR, error.message)
                        shutdownProcess(null)
                        return
                    }
                    commandExecutor.execute { startNative(config, replyTo) }
                }

                COMMAND_STATUS -> {
                    val running = !shuttingDown && nativeRunner?.isRunning() == true
                    sendReply(
                        message.replyTo,
                        RESPONSE_STATUS,
                        null,
                        Bundle().apply { putBoolean(KEY_RUNNING, running) }
                    )
                }

                COMMAND_STOP -> shutdownProcess(message.replyTo)
                else -> super.handleMessage(message)
            }
        }
    }

    private fun startNative(config: StartConfig, replyTo: Messenger?) {
        if (shuttingDown || nativeRunner != null) {
            runCatching { config.descriptor.close() }
            sendReply(replyTo, RESPONSE_ERROR, "Un pont tun2socks est déjà actif.")
            return
        }

        val runner = NativeTun2SocksRunner(this)
        nativeRunner = runner
        try {
            runner.start(
                vpnDescriptor = config.descriptor,
                mtu = config.mtu,
                vpnAddress = config.vpnAddress,
                netmask = config.netmask,
                socksAddress = config.socksAddress,
                udpgwAddress = config.udpgwAddress,
                forwardUdpThroughSocks = config.forwardUdpThroughSocks
            )
            if (!runner.isRunning()) {
                throw IllegalStateException("Le pont tun2socks distant ne répond pas.")
            }
            sendReply(replyTo, RESPONSE_STARTED)
        } catch (error: Throwable) {
            nativeRunner = null
            runCatching { runner.stop() }
            runCatching { config.descriptor.close() }
            sendReply(
                replyTo,
                RESPONSE_ERROR,
                error.message ?: "Le pont tun2socks n'a pas pu démarrer."
            )
            terminateOwnProcess()
        }
    }

    private fun shutdownProcess(replyTo: Messenger?) {
        if (shuttingDown) {
            sendReply(replyTo, RESPONSE_STOPPED)
            return
        }
        shuttingDown = true
        commandExecutor.execute {
            val runner = nativeRunner
            nativeRunner = null
            runCatching { runner?.stop() }
            sendReply(replyTo, RESPONSE_STOPPED)
            terminateOwnProcess()
        }
    }

    private fun terminateOwnProcess() {
        mainHandler.post {
            stopSelf()
            mainHandler.postDelayed(
                { Process.killProcess(Process.myPid()) },
                PROCESS_TERMINATION_DELAY_MS
            )
        }
    }

    private fun sendReply(
        messenger: Messenger?,
        what: Int,
        error: String? = null,
        extras: Bundle = Bundle()
    ) {
        if (messenger == null) return
        error?.takeIf { it.isNotBlank() }?.let { extras.putString(KEY_ERROR, it.take(220)) }
        try {
            messenger.send(Message.obtain(null, what).apply { data = extras })
        } catch (_: RemoteException) {
            // Le service VPN principal s'est arrêté ; ce processus sera fermé.
        }
    }

    @Suppress("DEPRECATION")
    private fun readConfig(source: Bundle): StartConfig {
        source.classLoader = ParcelFileDescriptor::class.java.classLoader
        val descriptor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelable(KEY_TUN_DESCRIPTOR, ParcelFileDescriptor::class.java)
        } else {
            source.getParcelable(KEY_TUN_DESCRIPTOR)
        } ?: throw IllegalStateException("Descripteur TUN absent.")

        return StartConfig(
            descriptor = descriptor,
            mtu = source.getInt(KEY_MTU),
            vpnAddress = source.getString(KEY_VPN_ADDRESS).orEmpty(),
            netmask = source.getString(KEY_NETMASK).orEmpty(),
            socksAddress = source.getString(KEY_SOCKS_ADDRESS).orEmpty(),
            udpgwAddress = source.getString(KEY_UDPGW_ADDRESS),
            forwardUdpThroughSocks = source.getBoolean(KEY_FORWARD_UDP)
        )
    }

    private data class StartConfig(
        val descriptor: ParcelFileDescriptor,
        val mtu: Int,
        val vpnAddress: String,
        val netmask: String,
        val socksAddress: String,
        val udpgwAddress: String?,
        val forwardUdpThroughSocks: Boolean
    )

    companion object {
        const val COMMAND_START = 1
        const val COMMAND_STATUS = 2
        const val COMMAND_STOP = 3
        const val RESPONSE_STARTED = 11
        const val RESPONSE_STATUS = 12
        const val RESPONSE_STOPPED = 13
        const val RESPONSE_ERROR = 14

        const val KEY_TUN_DESCRIPTOR = "tun_descriptor"
        const val KEY_MTU = "mtu"
        const val KEY_VPN_ADDRESS = "vpn_address"
        const val KEY_NETMASK = "netmask"
        const val KEY_SOCKS_ADDRESS = "socks_address"
        const val KEY_UDPGW_ADDRESS = "udpgw_address"
        const val KEY_FORWARD_UDP = "forward_udp"
        const val KEY_RUNNING = "running"
        const val KEY_ERROR = "error"

        private const val PROCESS_TERMINATION_DELAY_MS = 250L
    }
}
