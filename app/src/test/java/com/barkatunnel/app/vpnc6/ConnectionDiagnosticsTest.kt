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
    @Test fun nativeClassificationDoesNotRepeatSensitiveRawMessages() {
        val result = ConnectionDiagnostics.nativeSummary("secret password x509: private.invalid websocket: bad handshake token=private")
        assertEquals(2, result.size)
        assertFalse(result.joinToString().contains("private"))
        assertTrue(ConnectionDiagnostics.nativeSummary("custom error secret").single().contains("non classée"))
        assertTrue(ConnectionDiagnostics.nativeSummary("").single().contains("aucune erreur"))
    }
}
