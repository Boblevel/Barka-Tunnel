package com.barkatunnel.app.backend

import android.app.Application
import com.barkatunnel.app.vpnc6.BarkaVpnService
import com.barkatunnel.app.vpnc6.C6ProtocolEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class TunnelSessionTest {
    private fun client(port: Int) = TunnelControlTransport::class.java
        .getDeclaredMethod("clientFor", Int::class.javaPrimitiveType).apply { isAccessible=true }
        .invoke(TunnelControlTransport, port) as OkHttpClient

    @Test fun sessionBoundaryReplacesOldPoolButPreservesOtherPorts() {
        val first=client(19001); val other=client(19002)
        assertSame(first,client(19001))
        TunnelControlTransport.closeSession(19001)
        val next=client(19001)
        assertNotSame(first,next)
        assertNotSame(first.connectionPool,next.connectionPool)
        assertSame(other,client(19002))
        assertFalse(next.retryOnConnectionFailure)
        assertEquals(java.net.Proxy.Type.SOCKS,next.proxy!!.type())
        TunnelControlTransport.closeSession(19001);TunnelControlTransport.closeSession(19002)
    }

    @Test fun serviceStopCancelsInFlightControlCallAndReleasesSession() {
        val executor=Executors.newSingleThreadExecutor()
        val controller=Robolectric.buildService(BarkaVpnService::class.java).create()
        ServerSocket(0).use { server ->
            server.soTimeout=5000
            val old=client(server.localPort)
            val call=old.newCall(Request.Builder().url("https://example.invalid/").build())
            val result=executor.submit<Boolean> { try { call.execute().close();false } catch (_:java.io.IOException) { true } }
            try {
                server.accept().use { socket ->
                    socket.soTimeout=5000
                    assertEquals(5,socket.getInputStream().read())
                    server.close() // Keep the accepted request pending, but release the listening port.
                    var stopped=false
                    val engine=object:C6ProtocolEngine {
                        override val socksAddress="127.0.0.1:${server.localPort}"
                        override fun start() {}
                        override fun isRunning()=true
                        override fun stop() { stopped=true }
                    }
                    BarkaVpnService::class.java.getDeclaredField("engine").apply {isAccessible=true}.set(controller.get(),engine)
                    BarkaVpnService::class.java.getDeclaredMethod("stopTunnel").apply {isAccessible=true}.invoke(controller.get())
                    assertTrue(stopped)
                    assertTrue(call.isCanceled())
                    assertNotSame(old,client(server.localPort))
                }
                assertTrue(result.get(5,TimeUnit.SECONDS))
            } finally {
                call.cancel();TunnelControlTransport.closeSession(server.localPort)
                controller.destroy();executor.shutdownNow()
            }
        }
    }
}
