package com.barkatunnel.app.networkinfo

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33], application = Application::class)
class OrangeIpIndicatorTest {
    @Test fun defaultsPreserveOrangeAndDisablingAffectsOnlyTheTwoOrangeProfiles() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(OrangeIpIndicator.enabledFor(app, "orange_bf"))
        OrangeIpIndicator.synchronize(app, false)
        for (id in listOf("orange_bf", "orange_bf_v2")) assertFalse(OrangeIpIndicator.enabledFor(app, id))
        for (id in listOf("moov_bf", "moov_bf_v2", "telecel_bf", null)) assertTrue(OrangeIpIndicator.enabledFor(app, id))
        OrangeIpIndicator.synchronize(app, true)
        assertTrue(OrangeIpIndicator.enabledFor(app, "orange_bf_v2"))
    }

    @Test fun missingOrInvalidServerValueKeepsLastSetting() {
        val app = RuntimeEnvironment.getApplication()
        OrangeIpIndicator.synchronize(app, false)
        for (value in listOf(null, "true", 1)) OrangeIpIndicator.synchronize(app, value)
        assertFalse(OrangeIpIndicator.enabledFor(app.createConfigurationContext(app.resources.configuration), "orange_bf"))
    }
}
