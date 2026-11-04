package com.barkatunnel.app.backend

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TunnelControlDiagnosticTest {
    @Test fun distinguishesSocksTlsAndHttpStagesWithoutLeakingValues() {
        val call = OkHttpClient().newCall(Request.Builder().url("https://private.invalid/secret").build())
        val trace = TunnelControlTransport.ControlTrace()
        trace.connectStart(call, InetSocketAddress("127.0.0.1", 1080), Proxy.NO_PROXY)
        assertTrue(trace.failure(java.net.SocketTimeoutException("password=private")).message!!.contains("TCP/SOCKS • SocketTimeoutException"))
        trace.secureConnectStart(call)
        val error = javax.net.ssl.SSLHandshakeException("token=secret").apply {
            initCause(java.security.cert.CertificateException("private certificate"))
        }
        val message = trace.failure(error).message!!
        assertTrue(message.contains("TLS API • SSLHandshakeException > CertificateException"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("private"))
        trace.requestHeadersStart(call)
        assertTrue(trace.failure(java.io.IOException("secret")).message!!.contains("envoi HTTP"))
        trace.responseHeadersStart(call)
        assertTrue(trace.failure(java.net.SocketTimeoutException()).message!!.contains("attente HTTP"))
        assertTrue(message.length < 220)
    }

    @Test fun jsonAndUnknownExceptionsDoNotExposeMessagesOrCustomClassNames() {
        val trace = TunnelControlTransport.ControlTrace().apply { stage = "JSON" }
        assertTrue(trace.failure(org.json.JSONException("uuid=secret")).message!!.contains("JSON • JSONException"))
        class SecretIdentifierException : RuntimeException("https://secret.invalid")
        val message = trace.failure(SecretIdentifierException()).message!!
        assertFalse(message.contains("SecretIdentifier"))
        assertFalse(message.contains("secret.invalid"))
    }

    @Test fun realSocksRejectionStaysAFailureAndCarriesSafeStage() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = java.io.DataInputStream(socket.getInputStream())
                    assertEquals(5, input.readUnsignedByte())
                    val methods = ByteArray(input.readUnsignedByte())
                    input.readFully(methods)
                    socket.getOutputStream().write(byteArrayOf(5, 0xff.toByte()))
                    socket.getOutputStream().flush()
                }
            }
            try {
                try {
                    TunnelControlTransport.request("https://private.invalid/secret", null, server.localPort)
                    fail("A rejected SOCKS handshake must not authorize access")
                } catch (error: BarkaBackendException) {
                    val message = error.message.orEmpty()
                    assertTrue(message.contains("TCP/SOCKS"))
                    assertTrue(message.contains("SocketException"))
                    assertFalse(message.contains("private.invalid"))
                    assertFalse(message.contains("secret"))
                }
                peer.get(5, TimeUnit.SECONDS)
            } finally {
                TunnelControlTransport.closeSession(server.localPort)
                executor.shutdownNow()
            }
        }
    }
}
