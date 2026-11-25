package com.barkatunnel.app.ipfinder

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.os.Vibrator
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 33], application = Application::class)
class IpVibrationSettingsTest {
    private fun withSettings(block: (Activity, IpVibrationSettings) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        shadowOf(activity.getSystemService(Vibrator::class.java)).setHasVibrator(true)
        activity.getSharedPreferences("ip_vibration_help", 0).edit().clear().commit()
        for (key in listOf("vibrate_on", "haptic_feedback_enabled", "haptic_feedback_intensity"))
            Settings.System.putString(activity.contentResolver, key, null)
        val settings = IpVibrationSettings(activity)
        try { block(activity, settings) }
        finally { settings.close(); controller.pause().stop().destroy() }
    }

    @Test fun missingOemSettingsAreUnknownAndNeverAssumedDisabled() = withSettings { activity, settings ->
        assertEquals(IpVibrationSettings.State.UNKNOWN, settings.state())
        settings.offerIfNeeded()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        settings.offerIfNeeded()
        assertFalse(dialog.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
        val next = IpVibrationSettings(activity)
        next.offerIfNeeded()
        assertSame(dialog, ShadowDialog.getLatestDialog())
        next.close()
    }

    @Test fun disabledSettingsOfferNavigationButDoNotModifyThePhone() = withSettings { activity, settings ->
        Settings.System.putInt(activity.contentResolver, "vibrate_on", 0)
        assertEquals(IpVibrationSettings.State.DISABLED, settings.state())
        settings.offerIfNeeded()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Settings.ACTION_SOUND_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertEquals(0, Settings.System.getInt(activity.contentResolver, "vibrate_on"))
        settings.offerIfNeeded()
        assertFalse(dialog.isShowing)
    }

    @Test fun eachDisabledControlIsRecognizedAndCanBeEnabledOnReturn() = withSettings { activity, settings ->
        val keys = listOf("vibrate_on", "haptic_feedback_enabled", "haptic_feedback_intensity")
        for (key in keys) Settings.System.putInt(activity.contentResolver, key, 1)
        assertEquals(IpVibrationSettings.State.AVAILABLE, settings.state())
        for (key in keys) {
            Settings.System.putInt(activity.contentResolver, key, 0)
            assertEquals(IpVibrationSettings.State.DISABLED, settings.state())
            Settings.System.putInt(activity.contentResolver, key, 1)
            assertEquals(IpVibrationSettings.State.AVAILABLE, settings.state())
        }
        shadowOf(activity.getSystemService(Vibrator::class.java)).setHasVibrator(false)
        assertEquals(IpVibrationSettings.State.NO_HARDWARE, settings.state())
    }
}
