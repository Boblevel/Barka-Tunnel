package com.barkatunnel.app.vpnc6

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object SocksProbe {
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

            val hostBytes = destinationHost.toByteArray(Charsets.UTF_8)
            output.writeByte(0x05)
            output.writeByte(0x01)
            output.writeByte(0x00)
            output.writeByte(0x03)
            output.writeByte(hostBytes.size)
            output.write(hostBytes)
            output.writeShort(destinationPort)
            output.flush()

            if (input.readUnsignedByte() != 0x05 || input.readUnsignedByte() != 0x00) return false
            input.readUnsignedByte()
            when (input.readUnsignedByte()) {
                0x01 -> input.skipBytes(4)
                0x03 -> input.skipBytes(input.readUnsignedByte())
                0x04 -> input.skipBytes(16)
                else -> return false
            }
            input.skipBytes(2)
            true
        }
    } catch (_: Exception) {
        false
    }
}
