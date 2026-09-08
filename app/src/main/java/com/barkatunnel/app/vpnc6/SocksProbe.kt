package com.barkatunnel.app.vpnc6

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object SocksProbe {
    fun hasUsableInternet(
        proxyHost: String,
        proxyPort: Int,
        timeoutMs: Int
    ): Boolean = !Thread.currentThread().isInterrupted && (connectThrough(
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        destinationHost = "1.1.1.1",
        destinationPort = 443,
        timeoutMs = timeoutMs
    ) || (!Thread.currentThread().isInterrupted && connectThrough(
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        destinationHost = "8.8.8.8",
        destinationPort = 443,
        timeoutMs = timeoutMs
    )))

    fun connectThrough(
        proxyHost: String,
        proxyPort: Int,
        destinationHost: String = "1.1.1.1",
        destinationPort: Int = 443,
        timeoutMs: Int = 7_000
    ): Boolean = try {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            if (input.readUnsignedByte() != 0x05 || input.readUnsignedByte() != 0x00) return false

            output.writeByte(0x05)
            output.writeByte(0x01)
            output.writeByte(0x00)
            val ipv4 = parseIpv4(destinationHost)
            if (ipv4 != null) {
                output.writeByte(0x01)
                output.write(ipv4)
            } else {
                val hostBytes = destinationHost.toByteArray(Charsets.UTF_8)
                output.writeByte(0x03)
                output.writeByte(hostBytes.size)
                output.write(hostBytes)
            }
            output.writeShort(destinationPort)
            output.flush()

            if (input.readUnsignedByte() != 0x05 || input.readUnsignedByte() != 0x00) return false
            if (input.readUnsignedByte() != 0x00) return false
            when (input.readUnsignedByte()) {
                0x01 -> input.readFully(ByteArray(4))
                0x03 -> input.readFully(ByteArray(input.readUnsignedByte()))
                0x04 -> input.readFully(ByteArray(16))
                else -> return false
            }
            input.readUnsignedShort()
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun parseIpv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isBlank() || (part.length > 1 && part.startsWith('0'))) return null
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            bytes[index] = number.toByte()
        }
        return bytes
    }
}
