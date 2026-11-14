package com.barkatunnel.app.vpnc6
import android.app.Application
import com.barkatunnel.app.vpnprofile.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[29])
class GlobalProfiles25Test {
 private val raw="CONNECT [host]:[port] HTTP/1.1[crlf]proxy-connection: keep-alive[crlf]x-iorg-bsid: @FP_EVENTS[crlf][crlf]"
 @Test fun payloadPreservesBytesAndOverridesHeaders() {
  HttpConnectBridge("127.0.0.1",9,"target.example",443,mapOf("Wrong" to "old"),raw).use {
   assertEquals("CONNECT target.example:443 HTTP/1.1\r\nproxy-connection: keep-alive\r\nx-iorg-bsid: @FP_EVENTS\r\n\r\n",it.connectRequest().toString(Charsets.ISO_8859_1))
  }
 }
 @Test fun payloadRejectsExtraRequestAndDuplicateHeaders() {
  listOf(raw+"GET / HTTP/1.1[crlf][crlf]",raw.replace("x-iorg-bsid","proxy-connection")).forEach {
   try { ConnectPayload.expand(it,"target.example",443);fail("Invalid payload accepted") } catch (_:IllegalArgumentException) {}
  }
 }
 @Test fun certificateNameAndMuxAreIndependentOfSni() {
  val cfg=JSONObject().put("address","target.example").put("port",443).put("uuid","b71def49-88e2-4d13-afb6-c8b413ecb19e")
   .put("network","ws").put("security","tls").put("path","/80602af6").put("host","service.example").put("sni","routing.example")
   .put("verify_cert_name","service.example").put("proxy_host","127.0.0.1").put("proxy_port",8080).put("proxy_payload",raw).put("mux",true)
  val profile=VpnProfileConfigParser.parse(VpnProfileProtocol.VLESS,cfg.toString()) as VpnProfileConfig.Vless
  val build=XrayVlessEngine::class.java.getDeclaredMethod("buildConfig").apply {isAccessible=true}
  val json=build.invoke(XrayVlessEngine(RuntimeEnvironment.getApplication(),profile,10808)) as JSONObject
  val out=json.getJSONArray("outbounds").getJSONObject(0);val tls=out.getJSONObject("streamSettings").getJSONObject("tlsSettings")
  assertEquals("routing.example",tls.getString("serverName"));assertEquals("service.example",tls.getString("verifyPeerCertByName"))
  assertFalse(tls.optBoolean("allowInsecure"));assertTrue(out.getJSONObject("mux").getBoolean("enabled"))
  val other=build.invoke(XrayVlessEngine(RuntimeEnvironment.getApplication(),profile.copy(mux=false),10808)) as JSONObject
  assertFalse(other.getJSONArray("outbounds").getJSONObject(0).has("mux"))
 }
}
