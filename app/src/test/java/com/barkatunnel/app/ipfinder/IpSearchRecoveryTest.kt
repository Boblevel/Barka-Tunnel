package com.barkatunnel.app.ipfinder

import android.app.Application
import android.os.Bundle
import android.content.Intent
import android.widget.Button
import com.barkatunnel.app.R
import com.barkatunnel.app.ipfinder.assistant.BarkaAssistantService
import com.barkatunnel.app.ipfinder.assistant.BarkaAssistantSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class IpSearchRecoveryTest {
    @org.junit.Before fun grantManifestPermission() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    @Test fun noAddressStillAllowsRetry() {
        val controller = Robolectric.buildActivity(IpFinderActivity::class.java).setup()
        assertTrue(controller.get().findViewById<Button>(R.id.scanButton).isEnabled)
        controller.pause().stop().destroy()
    }
    @Test fun reusedVoiceSessionAcceptsTheNextCommand() {
        val session = BarkaAssistantSession(RuntimeEnvironment.getApplication())
        fun args(enabled: Boolean) = Bundle().apply {
            putString(BarkaAssistantService.KEY_SESSION_ACTION, BarkaAssistantService.SESSION_ACTION_SET_AIRPLANE_MODE)
            putBoolean(BarkaAssistantService.KEY_AIRPLANE_MODE_ENABLED, enabled)
        }
        session.onShow(args(true), 0)
        session.onShow(args(false), 0)
        val field = session.javaClass.getDeclaredField("requestedState").apply { isAccessible = true }
        assertFalse(field.getBoolean(session))
        session.onDestroy()
    }
    @Test fun restartedSearchResetsFailureBudget() {
        val controller = Robolectric.buildService(IpSearchService::class.java).create()
        val service = controller.get()
        val failures = service.javaClass.getDeclaredField("failures").apply { isAccessible = true }
        failures.setInt(service, 5)
        service.onStartCommand(Intent(), 0, 1)
        assertEquals(0, failures.getInt(service))
        controller.destroy()
    }
}
