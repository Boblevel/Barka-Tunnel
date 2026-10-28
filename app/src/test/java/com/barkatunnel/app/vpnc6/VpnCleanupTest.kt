package com.barkatunnel.app.vpnc6

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class VpnCleanupTest {
    @Test fun cancellationDoesNotSkipEngineCleanupAndPreservesInterrupt() {
        val controller=Robolectric.buildService(BarkaVpnService::class.java).create()
        val service=controller.get()
        var stopped=false
        val engine=object:C6ProtocolEngine {
            override val socksAddress="no-port"
            override fun start() {}
            override fun isRunning()=true
            override fun stop() { assertFalse(Thread.currentThread().isInterrupted);stopped=true }
        }
        BarkaVpnService::class.java.getDeclaredField("engine").apply { isAccessible=true }.set(service,engine)
        try {
            Thread.currentThread().interrupt()
            BarkaVpnService::class.java.getDeclaredMethod("stopTunnel").apply { isAccessible=true }.invoke(service)
            assertTrue(stopped)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted();controller.destroy() }
    }
}
