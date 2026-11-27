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
class VlessProfileReplacementTest {
    private fun outbound(json: JSONObject): JSONObject {
        val config = VpnProfileConfigParser.parse(VpnProfileProtocol.VLESS, json.toString()) as VpnProfileConfig.Vless
        val build = XrayVlessEngine::class.java.getDeclaredMethod("buildConfig").apply { isAccessible = true }
        val result = build.invoke(XrayVlessEngine(RuntimeEnvironment.getApplication(), config, 10808)) as JSONObject
        return result.getJSONArray("outbounds").getJSONObject(0)
    }

    @Test fun replacingFullJsonUsesNewAccountServerAndTransportFieldsWithoutOldOverrides() {
        val old = JSONObject().put("uri", "vless://aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000@old.example:443?type=ws&security=tls&host=old.example&sni=old.example&path=%2Fold")
            .put("ip", "192.0.2.1").put("pinned_cert_sha256", "ab".repeat(32))
        assertEquals("192.0.2.1", outbound(old).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        val next = JSONObject().put("uri", "vless://bbbb2222-cccc-4ddd-8eee-ffff00001111@new.example:8443?type=ws&security=tls&host=ws.example&sni=tls.example&path=%2Fnew%3Fed%3D2048&fp=chrome")
        val out = outbound(next)
        val server = out.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("new.example", server.getString("address"))
        assertEquals(8443, server.getInt("port"))
        assertEquals("bbbb2222-cccc-4ddd-8eee-ffff00001111", server.getJSONArray("users").getJSONObject(0).getString("id"))
        val stream = out.getJSONObject("streamSettings")
        assertEquals("ws.example", stream.getJSONObject("wsSettings").getString("host"))
        assertEquals("/new?ed=2048", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("tls.example", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("chrome", stream.getJSONObject("tlsSettings").getString("fingerprint"))
        assertFalse(stream.getJSONObject("tlsSettings").has("pinnedPeerCertSha256"))
        assertFalse(stream.getJSONObject("tlsSettings").has("allowInsecure"))
        assertNotEquals(ConnectionDiagnostics.fingerprint(old.toString()), ConnectionDiagnostics.fingerprint(next.toString()))
    }
}
