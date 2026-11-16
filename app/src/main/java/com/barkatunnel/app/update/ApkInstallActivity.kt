package com.barkatunnel.app.update

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import java.io.File
import java.util.concurrent.Executors

/** Give Android verified bytes through a session, rather than an OEM file viewer. */
class ApkInstallActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var sessionId = -1

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply {
            val density = resources.displayMetrics.density
            setPadding((24 * density).toInt(), (48 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
            setTextColor(getColor(R.color.barka_text))
            setBackgroundColor(getColor(R.color.barka_background))
            textSize = 17f
            setText(R.string.install_preparing)
        }
        setContentView(status)
        com.barkatunnel.app.ui.SystemBars.apply(this)
        sessionId = state?.getInt("session", -1) ?: -1
        if (intent.hasExtra(PackageInstaller.EXTRA_STATUS)) handleResult(intent)
        else if (state == null) prepare()
        else status.setText(R.string.install_waiting)
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("session", sessionId)
        super.onSaveInstanceState(state)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleResult(intent)
    }

    private fun prepare() {
        val path = intent.getStringExtra("apk") ?: run { finish(); return }
        val url = intent.getStringExtra("url").orEmpty()
        val version = intent.getLongExtra("version", 0)
        val app = applicationContext
        executor.execute {
            val installer = app.packageManager.packageInstaller
            var id = -1
            try {
                val apk = File(path).canonicalFile
                require(apk.path.startsWith(File(app.filesDir, "apk-updates").canonicalPath + File.separator))
                ApkVerifier.verify(app, apk, url, version)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(apk.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        apk.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                    val callback = Intent(app, ApkInstallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
                        setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }.toBundle() else null
                    val result = PendingIntent.getActivity(app, id, callback, flags, options)
                    session.commit(result.intentSender)
                }
                val committed = id
                runOnUiThread {
                    sessionId = committed
                    if (!isDestroyed && !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) status.setText(R.string.install_waiting)
                }
            } catch (error: Exception) {
                if (id >= 0) runCatching { installer.abandonSession(id) }
                runOnUiThread {
                    if (!isDestroyed) status.text = getString(R.string.install_failed_detail, error.message.orEmpty())
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleResult(result: Intent) {
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    try {
                        startActivity(confirmation)
                        result.removeExtra(PackageInstaller.EXTRA_STATUS)
                    }
                    catch (_: RuntimeException) { status.setText(R.string.update_open_failed) }
                } else status.setText(R.string.update_open_failed)
            }
            PackageInstaller.STATUS_SUCCESS -> finish()
            PackageInstaller.STATUS_FAILURE_ABORTED -> status.setText(R.string.install_cancelled)
            PackageInstaller.STATUS_FAILURE_STORAGE -> status.setText(R.string.install_storage)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> status.setText(R.string.install_conflict)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> status.setText(R.string.install_incompatible)
            else -> status.setText(R.string.update_open_failed)
        }
    }

    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
