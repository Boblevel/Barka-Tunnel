package com.barkatunnel.app.vpnprofile
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.barkatunnel.app.backend.BarkaBackendException
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class SshTlsProfileTest {
    private val config="""{"host":"ssh.example.org","sni":"ssh.example.org","username":"test","password":"test"}"""
    @Test fun defaultPortsAndReassignment() {
        val parsed=VpnProfileConfigParser.parse(VpnProfileProtocol.SSH_TLS,config) as VpnProfileConfig.SshTls
        assertEquals(443,parsed.port);assertEquals(7300,parsed.udpGwPort)
        for(network in listOf("moov_bf","moov_tiktok","orange_bf","telecel_bf"))
            for(protocol in VpnProfileProtocol.entries)assertTrue(VpnProfilePolicy.validate(network,protocol))
        assertFalse(VpnProfilePolicy.validate("unknown",VpnProfileProtocol.SSH_TLS))
    }
    @Test(expected=BarkaBackendException::class) fun insecureTlsRejected(){VpnProfileConfigParser.parse(VpnProfileProtocol.SSH_TLS,config.dropLast(1)+",\"allow_insecure\":true}")}
    @Test(expected=BarkaBackendException::class) fun invalidPortRejected(){VpnProfileConfigParser.parse(VpnProfileProtocol.SSH_TLS,config.dropLast(1)+",\"port\":70000}")}
}
