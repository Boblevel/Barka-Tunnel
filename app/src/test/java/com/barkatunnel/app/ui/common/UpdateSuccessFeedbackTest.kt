package com.barkatunnel.app.ui.common

import android.app.Activity
import android.app.Application
import android.content.Context
import com.barkatunnel.app.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdateSuccessFeedbackTest {
    @Before fun clearReceipts() {
        RuntimeEnvironment.getApplication().getSharedPreferences("barka_update_feedback", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ShadowToast.reset()
    }

    @Test fun returningAndRecreatingActivityDoesNotRepeatSameRevision() {
        val first = Robolectric.buildActivity(Activity::class.java).setup().visible()
        UpdateSuccessFeedback.showOnce(first.get(), R.string.startup_sync_current, "apk:107")
        UpdateSuccessFeedback.showOnce(first.get(), R.string.startup_sync_current, "apk:107")
        assertEquals(1, ShadowToast.shownToastCount())
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(Activity::class.java).setup().visible()
        UpdateSuccessFeedback.showOnce(second.get(), R.string.startup_sync_current, "apk:107")
        assertEquals(1, ShadowToast.shownToastCount())
        UpdateSuccessFeedback.showOnce(second.get(), R.string.startup_sync_current, "apk:108")
        assertEquals(2, ShadowToast.shownToastCount())
        second.pause().stop().destroy()
    }

    @Test fun persistedReceiptSurvivesNewFeedbackInstanceAndNewConfigurationIsVisible() {
        val app = RuntimeEnvironment.getApplication()
        val key = app.resources.getResourceEntryName(R.string.config_update_applied_message)
        app.getSharedPreferences("barka_update_feedback", Context.MODE_PRIVATE)
            .edit().putString(key, "orange:3").commit()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        UpdateSuccessFeedback.showOnce(activity.get(), R.string.config_update_applied_message, "orange:3")
        assertEquals(0, ShadowToast.shownToastCount())
        UpdateSuccessFeedback.showOnce(activity.get(), R.string.config_update_applied_message, "orange:4")
        UpdateSuccessFeedback.showOnce(activity.get(), R.string.config_update_applied_message, "orange:4")
        assertEquals(1, ShadowToast.shownToastCount())
        activity.pause().stop().destroy()
    }
}
