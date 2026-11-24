package com.barkatunnel.app.ipfinder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.Vibrator
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 33], application = Application::class)
class IpFinderAlertLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun reset() {
        for ((key, value) in listOf("active" to false, "foundIp" to null, "status" to "")) {
            IpSearchService::class.java.getDeclaredField(key).apply { isAccessible = true }.set(null, value)
        }
    }

    private fun withService(block: (IpSearchService) -> Unit) {
        shadowOf(app).grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val controller = Robolectric.buildService(IpSearchService::class.java).create()
        try {
            app.getSystemService(NotificationManager::class.java)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            shadowOf(app.getSystemService(Vibrator::class.java)).setHasVibrator(true)
            AppLogStore.clear(app)
            block(controller.get())
        } finally { controller.destroy() }
    }

    private fun vibrate(service: IpSearchService) =
        IpSearchService::class.java.getDeclaredMethod("vibrateFound").apply { isAccessible = true }
            .invoke(service) as Boolean

    private fun succeed(service: IpSearchService) {
        service.onStartCommand(Intent(app, IpSearchService::class.java), 0, 1)
        IpSearchService::class.java.getDeclaredMethod("success", String::class.java)
            .apply { isAccessible = true }.invoke(service, "10.161.1.2")
    }

    @Test fun stopsSearchingImmediatelyButKeepsServiceAliveForTheFullAlert() = withService { service ->
        succeed(service)
        assertFalse(IpSearchService.active)
        assertEquals("10.161.1.2", IpSearchService.foundIp)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(app.getSystemService(Vibrator::class.java)).isVibrating)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1199))
        assertFalse(shadowOf(service).isStoppedBySelf)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(741))
        assertTrue(AppLogStore.getAll(app).contains(app.getString(R.string.ip_alert_requested)))
    }

    @Test fun startingAnotherSearchCancelsThePreviousDelayedStop() = withService { service ->
        succeed(service)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        service.onStartCommand(Intent(app, IpSearchService::class.java), 0, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertTrue(IpSearchService.active)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun stopActionDoesNotWaitForTheAlert() = withService { service ->
        succeed(service)
        service.onStartCommand(Intent(app, IpSearchService::class.java).setAction(IpSearchService.STOP), 0, 2)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(IpSearchService.active)
    }

    @Test fun disabledNotificationsDoNotSuppressTheExplicitSearchAlert() = withService { service ->
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        succeed(service)
        assertTrue(shadowOf(app.getSystemService(Vibrator::class.java)).isVibrating)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals("10.161.1.2", IpSearchService.foundIp)
        assertTrue(AppLogStore.getAll(app).contains(app.getString(R.string.ip_alert_requested)))
    }

    @Test @Config(sdk = [28, 33]) fun existingSilentChannelIsPreservedWithoutBlockingTheExplicitAlert() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ip_search_found", "Existing", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false)
        })
        withService { service ->
            assertFalse(manager.getNotificationChannel("ip_search_found").shouldVibrate())
            assertTrue(vibrate(service))
            assertFalse(manager.getNotificationChannel("ip_search_found").shouldVibrate())
        }
    }

    @Test fun androidDecidesDndPolicyAndMissingHardwareIsDiagnosed() = withService { service ->
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertTrue(vibrate(service))
        assertTrue(AppLogStore.getAll(app).contains(app.getString(R.string.ip_alert_system_policy)))
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        shadowOf(app.getSystemService(Vibrator::class.java)).setHasVibrator(false)
        assertFalse(vibrate(service))
        assertTrue(AppLogStore.getAll(app).contains(app.getString(R.string.ip_alert_no_vibrator)))
    }

    @Test fun repeatedSuccessDoesNotSendAnotherAlert() = withService { service ->
        succeed(service)
        val vibrator = app.getSystemService(Vibrator::class.java)
        vibrator.cancel()
        IpSearchService::class.java.getDeclaredMethod("success", String::class.java)
            .apply { isAccessible = true }.invoke(service, "10.161.1.2")
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test @Config(sdk = [29, 33]) fun finishingSearchDoesNotCancelTheSecondPulse() = withService { service ->
        succeed(service)
        val vibrator = app.getSystemService(Vibrator::class.java)
        vibrator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertTrue(shadowOf(vibrator).isVibrating)
        assertFalse(IpSearchService.active)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test @Config(sdk = [29, 33]) fun restartingSearchCancelsTheOldSecondPulse() = withService { service ->
        succeed(service)
        val vibrator = app.getSystemService(Vibrator::class.java)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        service.onStartCommand(Intent(app, IpSearchService::class.java), 0, 2)
        vibrator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse(shadowOf(vibrator).isVibrating)
        assertTrue(IpSearchService.active)
    }
}
