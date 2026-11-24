package com.barkatunnel.app.ipfinder

import android.app.Application
import android.os.Looper
import android.os.Vibrator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33], application = Application::class)
class IpFoundVibrationTest {
    @Test fun sendsSecondPulseAt250msAndNeverRepeats() {
        val app = RuntimeEnvironment.getApplication()
        val vibrator = app.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        val alert = IpFoundVibration(app)
        assertTrue(alert.request(foreground = true))
        assertTrue(shadowOf(vibrator).isVibrating)
        vibrator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(249))
        assertFalse(shadowOf(vibrator).isVibrating)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(shadowOf(vibrator).isVibrating)
        vibrator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(shadowOf(vibrator).isVibrating)
        alert.cancelPending()
    }

    @Test fun stoppingOrReplacingAnAlertCancelsItsPendingPulse() {
        val app = RuntimeEnvironment.getApplication()
        val vibrator = app.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        val alert = IpFoundVibration(app)
        assertTrue(alert.request(foreground = false))
        alert.cancelPending()
        vibrator.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(shadowOf(vibrator).isVibrating)
    }
}
