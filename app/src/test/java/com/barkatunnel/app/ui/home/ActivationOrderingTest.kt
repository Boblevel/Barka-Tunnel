package com.barkatunnel.app.ui.home

import android.app.Application
import android.os.Looper
import com.barkatunnel.app.core.AppContainer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 35], application = Application::class)
class ActivationOrderingTest {
    @Test fun oldRefreshCannotEraseActivatedControllerState() {
        val context = RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val constructor = HomeAccessController::class.java.declaredConstructors.single {
            it.parameterTypes.size == 3 && it.parameterTypes.all { type -> type == kotlin.jvm.functions.Function0::class.java }
        }.apply { isAccessible = true }
        val access = constructor.newInstance({
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            HomeAccessState(false, 0)
        }, { HomeAccessState() }, { null }) as HomeAccessController
        val runtime = HomeRuntimeFactory.create(context, AppContainer(context)).copy(accessController = access)
        val controller = HomeController(runtime)
        val worker = Thread { controller.refreshAccess() }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.syncAccess(HomeAccessState(true, 86400))
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertEquals(86400L, controller.currentState().access.remainingSeconds)
    }

    @Test fun queuedTimerUpdateCannotEraseDisplayedActivation() {
        var displayed = -1L
        val timer = HomeTimerController({ displayed = it }, {})
        val worker = Thread { timer.syncAccessRemaining(0) }
        worker.start(); worker.join(5000)
        timer.syncAccessRemaining(86400)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(86400L, displayed)
    }

    @Test fun olderTrialRefreshKeepsConfirmedActivation() {
        val context = RuntimeEnvironment.getApplication()
        val before = HomeAccessSnapshotStore.activationGeneration
        HomeAccessSnapshotStore.saveActivation(context, HomeAccessState(true, 86400))
        val accepted = HomeAccessSnapshotStore.saveTrial(context, HomeAccessState(false, 0), before, false)
        assertTrue(accepted.remainingSeconds in 86390L..86400L)
    }
}
