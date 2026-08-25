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

class SshSocksProxy(
    private val sshHost: String,
    private val sshPort: Int,
    private val username: String,
    private val password: String,
    private val localPort: Int
) {
    private var session: Session? = null
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var running = false

    fun start() {
        val ssh = JSch().getSession(username, sshHost, sshPort).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "no")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            setServerAliveInterval(15_000)
            setServerAliveCountMax(3)
            connect(12_000)
        }
        session = ssh

        val listener = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        }
        serverSocket = listener
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
    }

    fun stop() {
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

    private fun handleClient(socket: Socket) {
        var channel: ChannelDirectTCPIP? = null
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
            channel.connect(10_000)

            sendReply(output, 0x00)
            socket.soTimeout = 0

            val remoteInput = channel.inputStream
            val remoteOutput = channel.outputStream
            val up = executor.submit { copy(socket.getInputStream(), remoteOutput) }
            val down = executor.submit { copy(remoteInput, socket.getOutputStream()) }
            runCatching { up.get() }
            runCatching { down.get() }
        } catch (_: Exception) {
            runCatching { DataOutputStream(socket.getOutputStream()).also { sendReply(it, 0x01) } }
        } finally {
            runCatching { channel?.disconnect() }
            clients -= socket
            runCatching { socket.close() }
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
