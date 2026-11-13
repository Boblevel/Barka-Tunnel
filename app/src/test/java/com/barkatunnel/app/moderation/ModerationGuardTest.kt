package com.barkatunnel.app.moderation

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ModerationGuardTest {
    @Test fun confirmedBlockClosesAllScreensAndUnblockPreservesAccess() {
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        val a = first.get(); val b = second.get()
        ModerationGuard.onActivityCreated(a, null)
        ModerationGuard.onActivityCreated(b, null)
        try {
            ModerationGuard.record(a, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(a.isFinishing)
            assertTrue(b.isFinishing)
            assertEquals(View.INVISIBLE, b.window.decorView.visibility)
            ModerationGuard.record(a, false)
            shadowOf(Looper.getMainLooper()).idle()
            val fresh = Robolectric.buildActivity(Activity::class.java).setup()
            ModerationGuard.onActivityCreated(fresh.get(), null)
            ModerationGuard.record(fresh.get(), false)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(fresh.get().isFinishing)
            ModerationGuard.onActivityDestroyed(fresh.get())
            fresh.pause().stop().destroy()
        } finally {
            ModerationGuard.onActivityDestroyed(a); ModerationGuard.onActivityDestroyed(b)
            first.pause().stop().destroy(); second.pause().stop().destroy()
        }
    }
}
