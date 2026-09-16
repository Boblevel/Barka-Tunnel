package com.barkatunnel.app.backend
import android.app.Application
import com.barkatunnel.app.vpnc6.BarkaVpnService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ConnectedApiRoutingTest {
    private fun state(name: String, value: Any?) {
        BarkaVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }
    @Test fun connectedApiUsesSocksWithoutDirectRetry() {
        val backend = BarkaBackendClient(RuntimeEnvironment.getApplication())
        val calls: List<() -> Unit> = listOf({ backend.startPayment("24h") },
            { backend.checkPaymentStatus("test-reference") }, { backend.redeemActivationCode("BARKA-TEST") },
            { backend.checkAppUpdate(110) }, { backend.checkAccess() },
            { backend.resellerPurchase("account", org.json.JSONObject().put("owner_key", "test")) },
            { backend.resellerPurchase("cancel", org.json.JSONObject().put("owner_key", "test").put("payment_reference", "BTR-test")) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (call in calls) ServerSocket(0).use { server ->
                server.soTimeout = 3000
                state("runtimeSocksPort", server.localPort)
                state("runtimeState", BarkaVpnService.RuntimeConnectionState.CONNECTED)
                val accepted = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream()
                        assertEquals(5, input.read())
                        val methods = input.read()
                        repeat(methods) { assertTrue(input.read() >= 0) }
                        socket.getOutputStream().write(byteArrayOf(5, 0xff.toByte()))
                        socket.getOutputStream().flush()
                    }
                    true
                }
                try { call(); fail("La panne du proxy doit être signalée") }
                catch (error: BarkaBackendException) { assertTrue(error.message.orEmpty().contains("Connectez-vous à un réseau")) }
                assertTrue(accepted.get(5, TimeUnit.SECONDS))
            }
        } finally {
            state("runtimeState", BarkaVpnService.RuntimeConnectionState.DISCONNECTED)
            state("runtimeSocksPort", null)
            executor.shutdownNow()
        }
    }
    @Test fun unavailableSessionsNeverExposeProxyPort() {
        try {
            state("runtimeSocksPort", 10808)
            for (s in listOf(BarkaVpnService.RuntimeConnectionState.DISCONNECTED, BarkaVpnService.RuntimeConnectionState.CONNECTING, BarkaVpnService.RuntimeConnectionState.DISCONNECTING)) {
                state("runtimeState", s); assertNull(BarkaVpnService.connectedSocksPort())
            }
        } finally {
            state("runtimeState", BarkaVpnService.RuntimeConnectionState.DISCONNECTED); state("runtimeSocksPort", null)
        }
    }
}
