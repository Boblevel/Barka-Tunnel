package com.barkatunnel.app.ipfinder

import android.app.Application
import android.provider.Settings
import android.os.Vibrator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 33], application = Application::class)
class IpFinderNoPromptTest {
    @Test fun disabledVibrationNeverOpensAPromptOnEntryOrReturn() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(Vibrator::class.java)).setHasVibrator(true)
        Settings.System.putInt(app.contentResolver, "vibrate_on", 0)
        Settings.System.putInt(app.contentResolver, "haptic_feedback_enabled", 0)
        val controller = Robolectric.buildActivity(IpFinderActivity::class.java).setup().visible()
        try {
            assertNull(ShadowDialog.getLatestDialog())
            repeat(3) {
                controller.pause().resume()
                assertNull(ShadowDialog.getLatestDialog())
            }
            assertNotNull(IpFinderActivity.visibleFeedbackView())
            assertEquals(0, Settings.System.getInt(app.contentResolver, "vibrate_on"))
        } finally { controller.pause().stop().destroy() }
        assertNull(IpFinderActivity.visibleFeedbackView())
    }
}
