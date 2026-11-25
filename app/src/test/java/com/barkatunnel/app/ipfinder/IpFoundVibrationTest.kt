package com.barkatunnel.app.ipfinder

import android.app.Application
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33], application = Application::class, shadows = [RecordingSystemVibrator::class])
class IpFoundVibrationTest {
    @Before fun clearRecordedRequests() = RecordingSystemVibrator.resetRequests()

    @Test fun sendsSecondPulseAt250msAndNeverRepeats() {
        val app = RuntimeEnvironment.getApplication()
        val vibrator = app.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        val alert = IpFoundVibration(app)
        assertTrue(alert.request(foreground = true))
        assertEquals(1, RecordingSystemVibrator.getRequestTimes().size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(249))
        assertEquals(1, RecordingSystemVibrator.getRequestTimes().size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        val times = RecordingSystemVibrator.getRequestTimes()
        assertEquals(2, times.size)
        assertEquals(250L, times[1] - times[0])
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        assertEquals(listOf(effect, effect), RecordingSystemVibrator.getRequestedEffects())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(2, RecordingSystemVibrator.getRequestTimes().size)
        alert.cancelPending()
    }

    @Test fun stoppingOrReplacingAnAlertCancelsItsPendingPulse() {
        val app = RuntimeEnvironment.getApplication()
        val vibrator = app.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        val alert = IpFoundVibration(app)
        assertTrue(alert.request(foreground = false))
        alert.cancelPending()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, RecordingSystemVibrator.getRequestTimes().size)
    }
}
