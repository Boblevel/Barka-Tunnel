package com.barkatunnel.app.vpnc6

import com.barkatunnel.app.backend.BackendAccessState
import com.barkatunnel.app.backend.BackendAppUpdate
import com.barkatunnel.app.update.AppUpdateGate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VpnUpdateContinuityTest {
    @After fun reset() = AppUpdateGate.clear()

    @Test fun mandatoryUpdateKeepsTransportButNotRevokedOrExpiredAccess() {
        AppUpdateGate.setRequired(BackendAppUpdate(true, true, true, 146, "next",
            "https://example.org/app.apk", "Update", downloadInApp = true))
        assertTrue(AppUpdateGate.isBlocked()) // Mandatory UI is preserved.
        assertFalse(shouldDisconnectForAccess(BackendAccessState(true, "paid", 3600, 7), 7))
        assertFalse(shouldDisconnectForAccess(null, 7)) // Temporary API outage is not revocation.
        assertTrue(shouldDisconnectForAccess(BackendAccessState(false, "expired", 0, 7), 7))
        assertTrue(shouldDisconnectForAccess(BackendAccessState(true, "paid", 3600, 8), 7))
    }
}
