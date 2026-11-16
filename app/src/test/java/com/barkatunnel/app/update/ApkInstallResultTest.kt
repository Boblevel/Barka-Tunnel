package com.barkatunnel.app.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 35], application = Application::class, qualifiers = "fr")
class ApkInstallResultTest {
    @Test fun androidFailuresExplainTheActionAndNeverClaimSuccess() {
        for ((code, message) in listOf(
            PackageInstaller.STATUS_FAILURE_STORAGE to R.string.install_storage,
            PackageInstaller.STATUS_FAILURE_CONFLICT to R.string.install_conflict,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to R.string.install_incompatible,
            PackageInstaller.STATUS_FAILURE_ABORTED to R.string.install_cancelled
        )) {
            val intent = Intent().putExtra(PackageInstaller.EXTRA_STATUS, code)
            val controller = Robolectric.buildActivity(ApkInstallActivity::class.java, intent).setup()
            try {
                val activity = controller.get()
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertEquals(activity.getString(message), (content.getChildAt(0) as TextView).text.toString())
                assertFalse(activity.isFinishing)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
