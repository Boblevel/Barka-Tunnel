package com.barkatunnel.app.update
import android.app.AlertDialog
import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BackendAppUpdate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppUpdateLinkTest {
    @Test fun normalRequiredAndChangedLinksOpenServerDestination() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        controller.setup().visible()
        try {
            val coordinator = AppUpdateCoordinator(activity)
            val apply = AppUpdateCoordinator::class.java.getDeclaredMethod("applyResult", BackendAppUpdate::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            for ((index, required) in listOf(false, true, true).withIndex()) {
                val url = "https://downloads.example.org/build-$index.apk?version=111"
                apply.invoke(coordinator, BackendAppUpdate(true, true, required, 999, "test", url, "Nouvelle version", "same-revision"), false)
                assertEquals(required, AppUpdateGate.isBlocked())
                if (required) {
                    assertEquals(url, AppUpdateGate.requiredUrl())
                    assertTrue(coordinator.showBlockingIfNeeded())
                }
                ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(url, shadowOf(activity).nextStartedActivity.data.toString())
            }
        } finally {
            AppUpdateGate.clear()
            controller.pause().stop().destroy()
        }
    }
}
