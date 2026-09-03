package com.barkatunnel.app.vpnc6

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Tun2SocksRunner(context: Context) {
    private val appContext = context.applicationContext
    private val stateLock = Any()
    private val commandLock = Any()
    private val replyThread = HandlerThread("BarkaTun2SocksReplies").apply { start() }
    private val replyMessenger = Messenger(ReplyHandler(replyThread.looper))

    @Volatile private var remoteReady = false
    @Volatile private var remoteMessenger: Messenger? = null
    @Volatile private var remoteBinder: IBinder? = null
    @Volatile private var pendingResponse: SettableFutureCompat<Response>? = null
    @Volatile private var connectionLatch: CountDownLatch? = null
    private var serviceConnection: ServiceConnection? = null
    private var bound = false

    fun start(
        vpnDescriptor: ParcelFileDescriptor,
        mtu: Int,
        vpnAddress: String,
        netmask: String,
        socksAddress: String,
        udpgwAddress: String?,
        forwardUdpThroughSocks: Boolean = false
    ) {
        synchronized(stateLock) {
            if (bound || remoteReady) {
                throw IllegalStateException("Un pont tun2socks est déjà actif.")
            }
        }

        bindRemoteService()
        try {
            val extras = Bundle().apply {
                putParcelable(Tun2SocksProcessService.KEY_TUN_DESCRIPTOR, vpnDescriptor)
                putInt(Tun2SocksProcessService.KEY_MTU, mtu)
                putString(Tun2SocksProcessService.KEY_VPN_ADDRESS, vpnAddress)
                putString(Tun2SocksProcessService.KEY_NETMASK, netmask)
                putString(Tun2SocksProcessService.KEY_SOCKS_ADDRESS, socksAddress)
                putString(Tun2SocksProcessService.KEY_UDPGW_ADDRESS, udpgwAddress)
                putBoolean(
                    Tun2SocksProcessService.KEY_FORWARD_UDP,
                    forwardUdpThroughSocks
                )
            }
            val response = sendAndAwait(
                command = Tun2SocksProcessService.COMMAND_START,
                extras = extras,
                timeoutMs = START_TIMEOUT_MS
            )
            if (response.what != Tun2SocksProcessService.RESPONSE_STARTED) {
                throw IllegalStateException(
                    response.error ?: "Le pont tun2socks distant n'a pas démarré."
                )
            }
            remoteReady = true
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun isRunning(): Boolean {
        if (!remoteReady || remoteBinder?.isBinderAlive != true) return false
        return try {
            val response = sendAndAwait(
                command = Tun2SocksProcessService.COMMAND_STATUS,
                timeoutMs = STATUS_TIMEOUT_MS
            )
            val running = response.what == Tun2SocksProcessService.RESPONSE_STATUS &&
                response.running
            remoteReady = running
            running
        } catch (_: Throwable) {
            remoteReady = false
            false
        }
    }

    fun stop() {
        val binder = remoteBinder
        val messenger = remoteMessenger
        if (messenger != null && remoteBinder?.isBinderAlive == true) {
            runCatching {
                sendAndAwait(
                    command = Tun2SocksProcessService.COMMAND_STOP,
                    timeoutMs = STOP_TIMEOUT_MS
                )
            }
        }
        remoteReady = false
        unbindRemoteService()
        awaitRemoteProcessExit(binder)
        replyThread.quitSafely()
    }

    private fun bindRemoteService() {
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                synchronized(stateLock) {
                    remoteBinder = binder
                    remoteMessenger = binder?.let(::Messenger)
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remoteReady = false
                remoteBinder = null
                remoteMessenger = null
                pendingResponse?.complete(
                    Response(
                        Tun2SocksProcessService.RESPONSE_ERROR,
                        "Le processus tun2socks s'est arrêté.",
                        false
                    )
                )
                connectionLatch?.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                onServiceDisconnected(name)
            }

            override fun onNullBinding(name: ComponentName?) {
                onServiceDisconnected(name)
            }
        }

        synchronized(stateLock) {
            serviceConnection = connection
            connectionLatch = latch
            bound = appContext.bindService(
                Intent(appContext, Tun2SocksProcessService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        }
        if (!bound) {
            unbindRemoteService()
            throw IllegalStateException("Android n'a pas démarré le processus tun2socks.")
        }

        try {
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("Le processus tun2socks ne répond pas.")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } finally {
            connectionLatch = null
        }
        if (remoteMessenger == null || remoteBinder?.isBinderAlive != true) {
            unbindRemoteService()
            throw IllegalStateException("La liaison tun2socks est indisponible.")
        }
    }

    private fun sendAndAwait(
        command: Int,
        extras: Bundle = Bundle(),
        timeoutMs: Long
    ): Response = synchronized(commandLock) {
        val messenger = remoteMessenger
            ?: throw IllegalStateException("Le processus tun2socks n'est pas lié.")
        val responseFuture = SettableFutureCompat<Response>()
        synchronized(stateLock) {
            if (pendingResponse != null) {
                throw IllegalStateException("Une commande tun2socks est déjà en cours.")
            }
            pendingResponse = responseFuture
        }
        try {
            messenger.send(Message.obtain(null, command).apply {
                data = extras
                replyTo = replyMessenger
            })
            responseFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: RemoteException) {
            throw IllegalStateException("Le processus tun2socks s'est arrêté.", error)
        } finally {
            synchronized(stateLock) {
                if (pendingResponse === responseFuture) {
                    pendingResponse = null
                }
            }
        }
    }

    private fun unbindRemoteService() {
        val connection: ServiceConnection?
        val shouldUnbind: Boolean
        synchronized(stateLock) {
            connection = serviceConnection
            shouldUnbind = bound
            serviceConnection = null
            bound = false
            remoteBinder = null
            remoteMessenger = null
        }
        if (shouldUnbind && connection != null) {
            runCatching { appContext.unbindService(connection) }
        }
    }

    private fun awaitRemoteProcessExit(binder: IBinder?) {
        binder ?: return
        val deadline = android.os.SystemClock.elapsedRealtime() + PROCESS_EXIT_TIMEOUT_MS
        while (binder.isBinderAlive && android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private inner class ReplyHandler(looper: android.os.Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            val response = Response(
                what = message.what,
                error = message.data?.getString(Tun2SocksProcessService.KEY_ERROR),
                running = message.data?.getBoolean(
                    Tun2SocksProcessService.KEY_RUNNING,
                    false
                ) ?: false
            )
            pendingResponse?.complete(response)
        }
    }

    private data class Response(
        val what: Int,
        val error: String?,
        val running: Boolean
    )

    companion object {
        private const val BIND_TIMEOUT_MS = 5_000L
        private const val START_TIMEOUT_MS = 8_000L
        private const val STATUS_TIMEOUT_MS = 1_500L
        private const val STOP_TIMEOUT_MS = 7_000L
        private const val PROCESS_EXIT_TIMEOUT_MS = 2_000L
    }
}
