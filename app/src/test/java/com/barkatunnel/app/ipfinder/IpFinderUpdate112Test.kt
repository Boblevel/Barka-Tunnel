package com.barkatunnel.app.ipfinder

import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [IpFinderUpdate112Test.AssistantRoles::class])
class IpFinderUpdate112Test {
    @Implements(RoleManager::class)
    class AssistantRoles {
        @Implementation fun isRoleAvailable(role: String) = available
        @Implementation fun isRoleHeld(role: String) = held
        @Implementation fun createRequestRoleIntent(role: String): Intent =
            Intent("android.app.role.action.REQUEST_ROLE").putExtra("android.app.role.extra.ROLE_NAME", role)
        companion object { var available = true; var held = false }
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences(IpFinderActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before fun reset() {
        shadowOf(context).grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        preferences.edit().clear().commit()
        AssistantRoles.available = true
        AssistantRoles.held = false
    }

    @Test fun newInstallRejectsRetiredPrefixButKeepsOtherAddresses() {
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.46.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.161.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.209.1.2"))
    }

    @Test fun savedPatternsAreMigratedWithoutRemovingSimilarPrefixesOrCustomRules() {
        preferences.edit().putString("search_pattern", "10.46;^10.46;=10.46;10.148,10.146\n^10.75;=192.168.1.2").commit()
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.46.1.2"))
        assertEquals("10.146;^10.75;=192.168.1.2;10.193;10.194", preferences.getString("search_pattern", ""))
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.146.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "192.168.1.2"))
        assertFalse(IpFinderActivity.isIpCompatible(context, "192.168.1.3"))
    }

    @Test fun onlyRetiredPatternsRestoreTheSupportedDefaults() {
        preferences.edit().putString("search_pattern", "10.46;^10.148").commit()
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.46.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.161.1.2"))
    }

    @Test fun addsNewPrefixesOnceWithoutDuplicatesAndPreservesLaterUserEdits() {
        preferences.edit().putString("search_pattern", "10.193;10.165").commit()
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.194.1.2"))
        assertEquals("10.193;10.165;10.194",preferences.getString("search_pattern", ""))
        preferences.edit().putString("search_pattern", "=192.168.1.1").commit()
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.194.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "192.168.1.1"))
    }

    private fun launchSelection(expectedAction: String) {
        val controller = Robolectric.buildActivity(IpFinderActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<View>(R.id.setAssistantButton).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals(expectedAction, intent.action)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(activity.findViewById<View>(R.id.setAssistantButton).isEnabled)
            activity.findViewById<View>(R.id.setAssistantButton).performClick()
            assertEquals(expectedAction, shadowOf(activity).nextStartedActivity.action)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun directAssistantSettingsHavePriorityEvenWhenRoleRequestIsAvailable() {
        launchSelection(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    @Test fun unavailableRoleFallsBackToAssistantSettings() {
        AssistantRoles.available = false
        launchSelection(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    @Test fun alreadySelectedAssistantCanStillOpenSettings() {
        AssistantRoles.held = true
        launchSelection(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}
