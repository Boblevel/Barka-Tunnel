package com.barkatunnel.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BackendAppUpdate
import com.barkatunnel.app.vpnc6.BarkaVpnService
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The request and received bytes survive activity/process recreation. */
class HomeApkDownload(private val activity: AppCompatActivity, root: View) {
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val card = root.findViewById<View>(R.id.apkDownloadCard)
    private val percent = root.findViewById<TextView>(R.id.apkDownloadPercent)
    private val progress = root.findViewById<ProgressBar>(R.id.apkDownloadProgress)
    private val status = root.findViewById<TextView>(R.id.apkDownloadStatus)
    private val action = root.findViewById<TextView>(R.id.apkDownloadAction)
    private val handler = Handler(Looper.getMainLooper())
    private var visible = false
    private var running = false
    private var cancelled = AtomicBoolean(false)
    private var ready: File? = null
    private var activeKey = ""
    private val retry = Runnable { resume() }

    init {
        action.setOnClickListener {
            when {
                ready != null -> install()
                running -> {
                    prefs.edit().putBoolean("paused", true).commit()
                    cancelled.set(true)
                    status.setText(R.string.download_paused)
                    action.isEnabled = false
                }
                else -> {
                    prefs.edit().putBoolean("paused", false).commit()
                    resume()
                }
            }
        }
    }

    fun stop() {
        visible = false
        cancelled.set(true)
        handler.removeCallbacks(retry)
    }

    @Suppress("DEPRECATION")
    fun resume() {
        visible = true
        handler.removeCallbacks(retry)
        val url = prefs.getString("url", "").orEmpty()
        val version = prefs.getLong("version", 0)
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val installedVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (!AppUpdateDestination.isValid(url) || version <= installedVersion) {
            cancelled.set(true)
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val key = key(url, version)
        if (running) {
            if (key != activeKey) cancelled.set(true)
            return
        }
        if (activeKey != key) ready = null
        activeKey = key
        val directory = File(activity.filesDir, "apk-updates/$key")
        val metadata = Properties()
        runCatching { File(directory, "download.properties").inputStream().use { metadata.load(it) } }
        val total = metadata.getProperty("total")?.toLongOrNull() ?: 0
        showProgress(File(directory, "update.part").length(), total)
        if (ready != null) { showReady(); return }
        action.isEnabled = true
        if (prefs.getBoolean("paused", false)) {
            status.setText(R.string.download_paused)
            action.setText(R.string.download_resume)
            return
        }
        running = true
        val cancellation = AtomicBoolean(false)
        cancelled = cancellation
        action.setText(R.string.download_pause)
        status.setText(R.string.download_connecting)
        val context = activity.applicationContext
        executor.execute {
            var completed: File? = null
            var failure: Exception? = null
            try {
                val builder = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS).followSslRedirects(false)
                BarkaVpnService.connectedSocksPort()?.let {
                    builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it)))
                }
                val apk = File(directory, "BarkaTunnel.apk")
                var lastUi = 0L
                val file = apk.takeIf { it.isFile } ?: ResumableApkDownload(builder.build())
                    .download(url, directory, { cancellation.get() }) { done, size ->
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 200 || done == size) {
                            lastUi = now
                            handler.post { if (visible && activeKey == key && !cancellation.get()) showProgress(done, size) }
                        }
                    }
                try { ApkVerifier.verify(context, file, url, version) }
                catch (error: Exception) { file.delete(); throw error }
                check(file == apk || file.renameTo(apk)) { "Impossible de préparer l’installation." }
                completed = apk
            } catch (error: Exception) { failure = error }
            val result = completed
            val error = failure
            handler.post {
                running = false
                if (activity.isDestroyed) return@post
                if (key != key(prefs.getString("url", "").orEmpty(), prefs.getLong("version", 0))) {
                    if (visible) resume()
                    return@post
                }
                ready = result
                if (!visible) return@post
                action.isEnabled = true
                when {
                    result != null -> showReady()
                    prefs.getBoolean("paused", false) -> {
                        status.setText(R.string.download_paused)
                        action.setText(R.string.download_resume)
                    }
                    cancellation.get() -> handler.post(retry)
                    error is IOException -> {
                        status.setText(R.string.download_retry)
                        action.setText(R.string.download_resume)
                        handler.postDelayed(retry, 10_000)
                    }
                    else -> {
                        status.setText(R.string.download_failed)
                        action.setText(R.string.download_resume)
                    }
                }
            }
        }
    }

    private fun showProgress(done: Long, total: Long) {
        val value = if (total > 0) (done.coerceAtMost(total) * 100 / total).toInt() else 0
        progress.progress = value
        percent.text = "$value%"
        if (total > 0) status.text = activity.getString(R.string.download_progress, done / 1048576, total / 1048576)
    }

    private fun showReady() {
        progress.progress = 100
        percent.text = "100%"
        status.setText(R.string.download_ready)
        action.setText(R.string.download_install)
        action.isEnabled = true
    }

    private fun install() {
        val apk = ready ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                status.setText(R.string.download_permission)
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.apk-provider", apk)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { status.setText(R.string.update_open_failed) }
    }

    companion object {
        private const val PREFS = "barka_apk_download"
        private val executor = Executors.newSingleThreadExecutor()
        private fun key(url: String, version: Long) = MessageDigest.getInstance("SHA-256")
            .digest("$url|$version".toByteArray()).joinToString("") { "%02x".format(it) }
        fun select(context: Context, update: BackendAppUpdate) {
            require(AppUpdateDestination.isValid(update.apkUrl) && update.latestVersionCode > 0)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", update.apkUrl).putLong("version", update.latestVersionCode)
                .putBoolean("paused", false).commit()
        }
        fun isSelected(context: Context, update: BackendAppUpdate): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getString("url", null) == update.apkUrl && prefs.getLong("version", 0) == update.latestVersionCode
        }
    }
}
