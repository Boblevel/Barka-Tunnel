package com.barkatunnel.app.tools

import org.junit.Assert.*
import org.junit.Test

class SharePasswordTest {
    @Test fun passwordsAreShortReadableAndRegenerated() {
        val passwords = (1..1000).map { VpnShareService.newPassword() }
        assertTrue(passwords.all { it.matches(Regex("[0-9]{8}")) })
        assertTrue(passwords.toSet().size > 990)
        assertEquals("Barka", VpnShareService.USERNAME)
    }
}
