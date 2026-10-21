package com.barkatunnel.app.update

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "fr-port-mdpi")
class HomeDownloadPresentationTest {
    @Test fun pausedProgressSurvivesControllerRecreationAndFitsPhoneWidths() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        controller.setup()
        try {
            val url = "https://updates.example.org/app.apk"
            val version = 999999L
            activity.getSharedPreferences("barka_apk_download", Context.MODE_PRIVATE).edit()
                .putString("url", url).putLong("version", version).putBoolean("paused", true).commit()
            val key = MessageDigest.getInstance("SHA-256").digest("$url|$version".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val dir = File(activity.filesDir, "apk-updates/$key").apply { mkdirs() }
            File(dir, "update.part").writeBytes(ByteArray(370))
            File(dir, "download.properties").writeText("total=1000\n")
            for (width in listOf(320, 360, 390, 480, 600)) {
                val root = activity.layoutInflater.inflate(R.layout.activity_main, null)
                val download = HomeApkDownload(activity, root)
                download.resume()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 640)
                val card = root.findViewById<View>(R.id.apkDownloadCard)
                assertEquals(View.VISIBLE, card.visibility)
                assertEquals("37%", root.findViewById<TextView>(R.id.apkDownloadPercent).text.toString())
                assertTrue(card.left >= 0 && card.right <= width)
                assertTrue(root.findViewById<View>(R.id.apkDownloadAction).height >= 48)
                assertTrue(root.findViewById<View>(R.id.homeContentViewport).height > 0)
                download.stop()
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun supportOffersContactWithoutDisplayingPhoneNumber() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_BarkaTunnel)
        controller.setup()
        try {
            val root = activity.layoutInflater.inflate(R.layout.activity_support, null)
            fun texts(view: View): List<String> = when (view) {
                is TextView -> listOf(view.text.toString())
                is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                else -> emptyList()
            }
            assertFalse(texts(root).any { it.contains("985") || it.contains("2404") })
            assertEquals("CONTACTER", root.findViewById<TextView>(R.id.contactSupportButton).text.toString())
        } finally { controller.pause().stop().destroy() }
    }
}
