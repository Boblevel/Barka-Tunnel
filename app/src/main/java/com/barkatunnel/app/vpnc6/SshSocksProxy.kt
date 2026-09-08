package com.barkatunnel.app.vpnc6

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class SshSocksProxy(
    private val sshHost: String,
    private val sshPort: Int,
    private val username: String,
    private val password: String,
    private val localPort: Int,
    private val connectTimeoutMs: Int = 12_000,
    private val isCancelled: () -> Boolean = { false }
) {
    @Volatile private var session: Session? = null
    @Volatile private var serverSocket: ServerSocket? = null
    private val stopped = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var running = false

    fun start() {
        var stage = "création session JSch"
        try {
            checkCancelled()
            val ssh = JSch().getSession(username, sshHost, sshPort).apply {
                setPassword(password)
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password,keyboard-interactive")
                setServerAliveInterval(15_000)
                setServerAliveCountMax(3)
            }
            session = ssh
            stage = "connexion SSH"
            connectSession(ssh)
            checkCancelled()

            stage = "ouverture SOCKS local"
            val listener = ServerSocket()
            serverSocket = listener
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
            checkCancelled()
            running = true

            executor.execute {
                while (running) {
                    try {
                        val socket = listener.accept()
                        clients += socket
                        executor.execute { handleClient(socket) }
                    } catch (_: Exception) {
                        if (running) stop()
                    }
                }
            }
        } catch (error: Exception) {
            stop()
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
            throw IllegalStateException(
                "SSH diagnostic • étape=$stage • hôte=$sshHost • portSSH=$sshPort • portSOCKS=$localPort • " +
                    "${error.javaClass.simpleName}:${error.message.orEmpty()}",
                error
            )
        }
    }

    fun stop() {
        stopped.set(true)
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        runCatching { session?.disconnect() }
        session = null
        executor.shutdownNow()
    }

    fun isRunning(): Boolean =
        !stopped.get() && running && session?.isConnected == true && serverSocket?.isClosed == false

    private fun checkCancelled() {
        if (stopped.get() || isCancelled() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Connexion annulée.")
        }
    }

    private fun connectSession(ssh: Session) {
        val future = executor.submit {
            try {
                checkCancelled()
                ssh.connect(connectTimeoutMs)
            } finally {
                if (stopped.get() || isCancelled() || Thread.currentThread().isInterrupted) {
                    ssh.disconnect()
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs.toLong())
        try {
            while (true) {
                checkCancelled()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) throw TimeoutException("Délai de connexion SSH dépassé.")
                try {
                    future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(200L)), TimeUnit.NANOSECONDS)
                    return
                } catch (_: TimeoutException) {
                    // Vérifier l'annulation même pendant la négociation SSH.
                }
            }
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: error)
        } finally {
            if (!future.isDone) {
                stopped.set(true)
                ssh.disconnect()
                future.cancel(true)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var channel: ChannelDirectTCPIP? = null
        var connectedReply = false
        var upstream: Future<*>? = null
        try {
            socket.soTimeout = 20_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            if (input.readUnsignedByte() != 0x05) return
            val methodCount = input.readUnsignedByte()
            repeat(methodCount) { input.readUnsignedByte() }
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            if (input.readUnsignedByte() != 0x05) return
            val command = input.readUnsignedByte()
            input.readUnsignedByte()
            if (command != 0x01) {
                sendReply(output, 0x07)
                return
            }

            val host = when (input.readUnsignedByte()) {
                0x01 -> ByteArray(4).also { input.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress ?: return }
                0x03 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                0x04 -> ByteArray(16).also { input.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress ?: return }
                else -> {
                    sendReply(output, 0x08)
                    return
                }
            }
            val port = input.readUnsignedShort()

            val activeSession = session?.takeIf { it.isConnected } ?: run {
                sendReply(output, 0x01)
                return
            }
            channel = activeSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(host)
            channel.setPort(port)
            channel.setOrgIPAddress("127.0.0.1")
            channel.setOrgPort(socket.localPort)
            val remoteInput = channel.inputStream
            val remoteOutput = channel.outputStream
            channel.connect(10_000)

            sendReply(output, 0x00)
            connectedReply = true
            socket.soTimeout = 0

            val activeChannel = channel
            upstream = executor.submit {
                try {
                    copy(socket.getInputStream(), remoteOutput)
                } catch (_: Exception) {
                    runCatching { activeChannel.disconnect() }
                    runCatching { socket.close() }
                } finally {
                    // Fermer ce flux transmet SSH EOF sans perdre la réponse distante.
                    runCatching { remoteOutput.close() }
                }
            }
            copy(remoteInput, socket.getOutputStream())
        } catch (_: Exception) {
            if (!connectedReply) {
                runCatching { DataOutputStream(socket.getOutputStream()).also { sendReply(it, 0x01) } }
            }
        } finally {
            runCatching { channel?.disconnect() }
            clients -= socket
            runCatching { socket.close() }
            upstream?.cancel(true)
        }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun sendReply(output: DataOutputStream, code: Int) {
        output.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        output.flush()
    }
}
