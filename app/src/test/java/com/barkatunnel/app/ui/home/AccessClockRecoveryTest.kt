package com.barkatunnel.app.ui.home

import android.app.Application
import android.content.Context
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28], application=Application::class)
class AccessClockRecoveryTest {
    @Test fun unavailableBootCounterDoesNotUseWrongWallClockWithinProcess() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, "boot_count", -1)
        HomeAccessSnapshotStore.saveActivation(context, HomeAccessState(true, 3600, "Actif"))
        context.getSharedPreferences("barka_access_snapshot", Context.MODE_PRIVATE).edit().putLong("expiry_ms", 1).commit()
        assertTrue(HomeAccessSnapshotStore.restore(context)!!.remainingSeconds in 3590L..3600L)
    }
    @Test fun oldRefreshCannotEraseNewActivation() {
        val context = RuntimeEnvironment.getApplication()
        val generation = HomeAccessSnapshotStore.activationGeneration
        HomeAccessSnapshotStore.saveActivation(context, HomeAccessState(true, 3600, "Actif"))
        val result = HomeAccessSnapshotStore.saveRefresh(context, HomeAccessState(false, 0, "Inactif"), generation)
        assertTrue(result.allowed)
        assertTrue(result.remainingSeconds > 0)
    }
}
