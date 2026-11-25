package com.barkatunnel.app.ipfinder

import android.app.Application
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.view.View
import android.view.HapticFeedbackConstants
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

    @Test fun sendsOneVpnButtonClickAndNeverRepeats() {
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
        assertEquals(1, times.size)
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        assertEquals(listOf(effect), RecordingSystemVibrator.getRequestedEffects())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, RecordingSystemVibrator.getRequestTimes().size)
    }

    @Test fun backgroundAlertHasNoDelayedSecondPulse() {
        val app = RuntimeEnvironment.getApplication()
        val vibrator = app.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        val alert = IpFoundVibration(app)
        assertTrue(alert.request(foreground = false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, RecordingSystemVibrator.getRequestTimes().size)
    }

    @Test fun acceptedVpnStyleViewFeedbackDoesNotAlsoCallVibrator() {
        val app = RuntimeEnvironment.getApplication()
        var calls = 0
        val view = object : View(app) {
            override fun isShown() = true
            override fun performHapticFeedback(kind: Int, flags: Int): Boolean {
                calls++
                assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, kind)
                @Suppress("DEPRECATION")
                val expected = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                assertEquals(expected, flags)
                return true
            }
        }
        assertTrue(IpFoundVibration(app).request(true, view))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, calls)
        assertTrue(RecordingSystemVibrator.getRequestTimes().isEmpty())
    }

    @Test fun rejectedViewFeedbackFallsBackToExactlyOneClick() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(Vibrator::class.java)).setHasVibrator(true)
        val view = object : View(app) {
            override fun isShown() = true
            override fun performHapticFeedback(kind: Int, flags: Int) = false
        }
        assertTrue(IpFoundVibration(app).request(true, view))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)),
            RecordingSystemVibrator.getRequestedEffects())
    }

}
