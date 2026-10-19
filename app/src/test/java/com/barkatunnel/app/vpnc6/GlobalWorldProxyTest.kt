package com.barkatunnel.app.vpnc6

import android.app.Application
import com.barkatunnel.app.vpnprofile.VpnProfileConfig
import com.barkatunnel.app.vpnprofile.VpnProfileConfigParser
import com.barkatunnel.app.vpnprofile.VpnProfileProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class GlobalWorldProxyTest {
    private val config = """{"address":"alt13.yt3.ggpht.com","port":443,
      "uuid":"aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000","network":"ws",
      "path":"/Telegram/@AM2_D3/@AHMAD3214","security":"tls",
      "host":"ahmed-vidgg-294397537401.us-central1.run.app","sni":"alt13.yt3.ggpht.com",
      "proxy_host":"157.240.9.39","proxy_port":8080,
      "proxy_headers":{"X-Connected-To":"34.143.72.2","X-Org-Bsid":"@AM2_D3"}}"""

    @Test fun vlessUsesHttpConnectProxyForItsUnderlyingWebSocket() {
        val profile = VpnProfileConfigParser.parse(VpnProfileProtocol.VLESS, config) as VpnProfileConfig.Vless
        val engine = XrayVlessEngine(RuntimeEnvironment.getApplication(), profile, 10801)
        val build = XrayVlessEngine::class.java.getDeclaredMethod("buildConfig").apply { isAccessible = true }
        val root = build.invoke(engine) as JSONObject
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("front-proxy", stream.getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals("ahmed-vidgg-294397537401.us-central1.run.app",
            stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
        assertEquals("alt13.yt3.ggpht.com", stream.getJSONObject("tlsSettings").getString("serverName"))
        val proxy = root.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("http", proxy.getString("protocol"))
        assertEquals("157.240.9.39", proxy.getJSONObject("settings").getString("address"))
        assertEquals("34.143.72.2", proxy.getJSONObject("settings").getJSONObject("headers").getString("X-Connected-To"))
    }

    @Test(expected = com.barkatunnel.app.backend.BarkaBackendException::class)
    fun proxyCannotRemoveTls() {
        VpnProfileConfigParser.parse(VpnProfileProtocol.VLESS,
            config.replace("\"security\":\"tls\"", "\"security\":\"none\""))
    }
}
