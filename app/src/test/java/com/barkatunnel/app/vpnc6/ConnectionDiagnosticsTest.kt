package com.barkatunnel.app.vpnc6

import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test fun metadataCannotExposeCredentialsOrInjectedLines() {
        assertEquals("[masqué]", ConnectionDiagnostics.host("private@host.invalid/path?token=abc"))
        assertEquals("[masqué]", ConnectionDiagnostics.host("host\nInjected"))
        assertEquals("2001:db8::1", ConnectionDiagnostics.host("2001:db8::1"))
        assertEquals("proxy.invalid", ConnectionDiagnostics.host("proxy.invalid"))
        for (key in listOf("Authorization", "Cookie", "X-Secret", "X-Iorg-Bsid", "User-Agent")) {
            assertFalse(ConnectionDiagnostics.header(key, "secret-value").contains("secret-value"))
        }
        assertFalse(ConnectionDiagnostics.header("Injected\nline", "secret").contains("\n"))
        assertTrue(ConnectionDiagnostics.header("Connection", "keep-alive").contains("keep-alive"))
        assertNotEquals(ConnectionDiagnostics.fingerprint("profile-a"), ConnectionDiagnostics.fingerprint("profile-b"))
    }
    @Test fun nativeErrorsRemainDetailedWithoutTokens() {
        val result = ConnectionDiagnostics.nativeSummary("x509: proxy.invalid websocket: bad handshake token=private")
        assertEquals(3, result.size)
        assertFalse(result.joinToString().contains("private"))
        assertTrue(result.joinToString().contains("proxy.invalid"))
        assertTrue(ConnectionDiagnostics.nativeSummary("custom transport error").single().contains("custom transport error"))
        assertTrue(ConnectionDiagnostics.nativeSummary("").single().contains("aucune erreur"))
    }
}
