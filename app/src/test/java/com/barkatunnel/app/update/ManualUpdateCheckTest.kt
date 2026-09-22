package com.barkatunnel.app.update

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.backend.BackendAppUpdate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Implements(value=BarkaBackendClient::class, isInAndroidSdk=false)
class UpdateBackendShadow {
    companion object {
        var available = true
        var entered = CountDownLatch(1)
        var release = CountDownLatch(0)
    }
    @Implementation fun checkAppUpdate(currentVersionCode: Long, socksPort: Int?): BackendAppUpdate {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        return BackendAppUpdate(true, available, false, if(available) currentVersionCode + 1 else currentVersionCode,
            "2.0.4.2", "https://example.org/BarkaTunnel.apk", "Mise à jour disponible")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, shadows=[UpdateBackendShadow::class])
class ManualUpdateCheckTest {
    class Screen : AppCompatActivity() {
        override fun onCreate(state: android.os.Bundle?) {
            setTheme(R.style.Theme_BarkaTunnel)
            super.onCreate(state)
        }
    }
    @Before fun reset() {
        UpdateBackendShadow.available = true
        UpdateBackendShadow.entered = CountDownLatch(1)
        UpdateBackendShadow.release = CountDownLatch(0)
    }
    private fun waitDone(coordinator: AppUpdateCoordinator) {
        val field=AppUpdateCoordinator::class.java.getDeclaredField("checking").apply { isAccessible=true }
        val end=System.nanoTime()+5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if(!field.getBoolean(coordinator)) return
            Thread.sleep(10)
        } while(System.nanoTime()<end)
        fail("Update check timed out")
    }
    @Test fun dismissedUpdateReappearsOnEveryManualCheckUntilInstalled() {
        val controller=Robolectric.buildActivity(Screen::class.java).setup()
        try {
            val coordinator=AppUpdateCoordinator(controller.get())
            repeat(2) {
                coordinator.check(showNoUpdate=true)
                waitDone(coordinator)
                val dialog=ShadowAlertDialog.getLatestAlertDialog()
                assertNotNull(dialog);assertTrue(dialog.isShowing)
                dialog.dismiss()
            }
            UpdateBackendShadow.available=false
            coordinator.check(showNoUpdate=true)
            waitDone(coordinator)
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun manualClickDuringBackgroundCheckStillShowsDismissedUpdate() {
        val controller=Robolectric.buildActivity(Screen::class.java).setup()
        try {
            val coordinator=AppUpdateCoordinator(controller.get())
            coordinator.check(showNoUpdate=true);waitDone(coordinator)
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            UpdateBackendShadow.entered=CountDownLatch(1)
            UpdateBackendShadow.release=CountDownLatch(1)
            coordinator.check(force=true)
            assertTrue(UpdateBackendShadow.entered.await(5,TimeUnit.SECONDS))
            coordinator.check(showNoUpdate=true,force=true)
            UpdateBackendShadow.release.countDown()
            waitDone(coordinator)
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        } finally { UpdateBackendShadow.release.countDown();controller.pause().stop().destroy() }
    }
}
