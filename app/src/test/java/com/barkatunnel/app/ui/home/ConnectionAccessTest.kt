package com.barkatunnel.app.ui.home

import org.junit.Assert.*
import org.junit.Test

class ConnectionAccessTest {
    private fun controller(
        refresh: () -> HomeAccessState,
        snapshot: () -> HomeAccessState?
    ): HomeAccessController {
        // Exercise the production decision without making a real API request.
        val constructor = HomeAccessController::class.java.declaredConstructors.single {
            it.parameterTypes.size == 3 && it.parameterTypes.all { type -> type == kotlin.jvm.functions.Function0::class.java }
        }
        constructor.isAccessible = true
        return constructor.newInstance(refresh, { HomeAccessState() }, snapshot) as HomeAccessController
    }

    @Test fun validMobileSnapshotAvoidsDirectRequestButNormalRefreshStillRuns() {
        var calls = 0
        val cached = HomeAccessState(true, 300, "Accès actif")
        val denied = HomeAccessState(false, 0, "Révoqué")
        val access = controller({ calls++; denied }, { cached })
        assertEquals(cached, access.prepareConnectionAccess())
        assertEquals(0, calls)
        assertEquals(denied, access.refreshAccess())
        assertEquals(1, calls)
    }

    @Test fun missingExpiredOrDeniedSnapshotUsesExistingServerCheck() {
        for (snapshot in listOf(null, HomeAccessState(false, 120), HomeAccessState(true, 0), HomeAccessState(true, -1))) {
            var calls = 0
            val live = HomeAccessState(true, 80)
            assertEquals(live, controller({ calls++; live }, { snapshot }).prepareConnectionAccess())
            assertEquals(1, calls)
        }
    }
}
