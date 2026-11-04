package com.barkatunnel.app.backend

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class AccessPayloadTest {
    @Test fun serverDurationAndServerDatesAreIndependentOfPhoneClock() {
        assertEquals(3600L, AccessPayload.remaining(JSONObject("""{"allowed":true,"remaining_seconds":3600}""")))
        assertEquals(3600L, AccessPayload.remaining(JSONObject("""{"allowed":true,"remaining_seconds":0,"server_time":"2026-11-04T10:00:00Z","expires_at":"2026-11-04T11:00:00Z"}""")))
        assertEquals(0L, AccessPayload.remaining(JSONObject("""{"allowed":false,"remaining_seconds":3600}""")))
    }
    @Test fun malformedSuccessCannotOverwriteConfirmedAccessWithZero() {
        for (json in listOf("{}", """{"allowed":true,"remaining_seconds":0}""")) {
            try { AccessPayload.remaining(JSONObject(json)); fail("Expected invalid response") }
            catch (_: BarkaBackendException) { }
        }
    }
}
