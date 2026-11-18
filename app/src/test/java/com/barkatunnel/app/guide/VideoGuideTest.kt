package com.barkatunnel.app.guide

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 33], application = Application::class, qualifiers = "fr-port-mdpi")
class VideoGuideTest {
    private fun children(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()

    private fun withPlayer(block: (Activity, AlertDialog, WebView, TextView) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        VideoGuide.show(activity, "abcdefghijk")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            val views = children(dialog.window!!.decorView)
            val web = views.filterIsInstance<WebView>().single()
            val status = views.filterIsInstance<TextView>().single {
                it.text == activity.getString(R.string.video_guide_loading)
            }
            block(activity, dialog, web, status)
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }

    @Test fun slowVideoRemainsVisibleAndCanBecomeReadyLater() = withPlayer { activity, _, web, status ->
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals(View.VISIBLE, web.visibility)
        assertEquals(activity.getString(R.string.video_guide_slow), status.text.toString())
        shadowOf(web).webChromeClient.onReceivedTitle(web, "guide-1-ready")
        assertEquals(activity.getString(R.string.video_guide_intro), status.text.toString())
    }

    @Test fun errorsStayVisibleAndRetryIgnoresOldCallbacks() = withPlayer { activity, dialog, web, status ->
        val client = shadowOf(web).webChromeClient
        client.onReceivedTitle(web, "guide-1-error-150")
        assertEquals(activity.getString(R.string.video_guide_restricted), status.text.toString())
        assertEquals(View.VISIBLE, web.visibility)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        client.onReceivedTitle(web, "guide-1-error-153")
        assertEquals(activity.getString(R.string.video_guide_loading), status.text.toString())
        client.onReceivedTitle(web, "guide-2-ready")
        assertEquals(activity.getString(R.string.video_guide_intro), status.text.toString())
        assertTrue(dialog.isShowing)
    }

    @Test fun dialogHasOnlyRetryAndCloseAndNeverLaunchesAnExternalActivity() = withPlayer { activity, dialog, _, _ ->
        assertEquals(View.GONE, dialog.getButton(DialogInterface.BUTTON_NEUTRAL).visibility)
        assertEquals(activity.getString(R.string.video_guide_retry), dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun lateTimeoutDoesNotReplaceTheActualYoutubeError() = withPlayer { activity, _, web, status ->
        shadowOf(web).webChromeClient.onReceivedTitle(web, "guide-1-error-153")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals(activity.getString(R.string.video_guide_identity), status.text.toString())
        assertEquals(View.VISIBLE, web.visibility)
    }

    @Test fun closingCancelsPendingUiUpdates() = withPlayer { _, dialog, web, status ->
        val client = shadowOf(web).webChromeClient
        val before = status.text.toString()
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        client.onReceivedTitle(web, "guide-1-error-153")
        assertEquals(before, status.text.toString())
        assertTrue(shadowOf(web).wasDestroyCalled())
    }

    @Test fun pageUsesInstalledAppIdentityAndRejectsInjection() {
        val origin = VideoGuidePage.origin("com.barkatunnel.app")
        assertEquals("https://com.barkatunnel.app", origin)
        assertTrue(VideoGuidePage.html("abcdefghijk", origin, 1).contains("origin: '$origin'"))
        assertThrows(IllegalArgumentException::class.java) { VideoGuidePage.html("';alert(1)", origin, 1) }
        assertThrows(IllegalArgumentException::class.java) { VideoGuidePage.origin("bad'<script>") }
    }
}
