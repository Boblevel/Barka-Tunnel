package com.barkatunnel.app.update

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.backend.BackendAppUpdate
import com.barkatunnel.app.journal.AppLogStore

class AppUpdateCoordinator(
    private val activity: AppCompatActivity,
    private val backendClient: BarkaBackendClient = BarkaBackendClient(activity)
) {

    @Volatile
    private var checking = false
    private var manualResultRequested = false

    @Volatile
    private var lastCheckAt = 0L

    private var updateDialog: AlertDialog? = null
    private var lastPresentedUpdate = ""

    private val callbackLock = Any()
    private val waitingCallbacks = mutableListOf<(BackendAppUpdate?) -> Unit>()

    fun check(
        showNoUpdate: Boolean = false,
        force: Boolean = false,
        onResult: ((BackendAppUpdate?) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        synchronized(callbackLock) {
            if (checking) {
                manualResultRequested = manualResultRequested || showNoUpdate
                onResult?.let(waitingCallbacks::add)
                return
            }
            if (!force && !showNoUpdate && now - lastCheckAt < 5 * 60 * 1000L) {
                onResult?.invoke(null)
                return
            }
            checking = true
            manualResultRequested = showNoUpdate
        }
        Thread {
            try {
                val update = backendClient.checkAppUpdate(currentVersionCode())
                lastCheckAt = System.currentTimeMillis()
                activity.runOnUiThread {
                    try {
                        applyResult(update, synchronized(callbackLock) { manualResultRequested })
                    } finally {
                        finishCheck(update, onResult)
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (synchronized(callbackLock) { manualResultRequested }) {
                        Toast.makeText(
                            activity,
                            e.message ?: activity.getString(R.string.update_check_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    finishCheck(null, onResult)
                }
            }
        }.start()
    }

    private fun finishCheck(
        result: BackendAppUpdate?,
        primaryCallback: ((BackendAppUpdate?) -> Unit)?
    ) {
        val callbacks = synchronized(callbackLock) {
            checking = false
            manualResultRequested = false
            buildList {
                primaryCallback?.let(::add)
                addAll(waitingCallbacks)
            }.also { waitingCallbacks.clear() }
        }
        callbacks.forEach { it(result) }
    }

    fun showBlockingIfNeeded(): Boolean {
        if (!AppUpdateGate.isBlocked()) return false
        showDialog(
            BackendAppUpdate(
                enabled = true,
                updateAvailable = true,
                forceUpdate = true,
                latestVersionCode = 0L,
                latestVersionName = "",
                apkUrl = AppUpdateGate.requiredUrl(),
                message = AppUpdateGate.requiredMessage()
            )
        )
        return true
    }

    private fun applyResult(update: BackendAppUpdate, showNoUpdate: Boolean) {
        if (!update.enabled || !update.updateAvailable || update.latestVersionCode <= currentVersionCode()) {
            AppUpdateGate.clear()
            lastPresentedUpdate = ""
            updateDialog?.dismiss()
            updateDialog = null
            RemoteUpdateWorker.clearApkNotification(activity)
            if (showNoUpdate) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_none),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        if (update.forceUpdate) {
            AppUpdateGate.setRequired(update.apkUrl, update.message)
        } else {
            AppUpdateGate.clear()
        }

        val key = "${update.latestVersionCode}:${update.updatedAt}:${update.forceUpdate}:${update.apkUrl}:${update.message}"
        if (!showNoUpdate && key == lastPresentedUpdate &&
            (!update.forceUpdate || updateDialog?.isShowing == true)) return
        lastPresentedUpdate = key
        AppLogStore.add(
            activity,
            "Mise à jour disponible${if (update.forceUpdate) " • obligatoire" else ""}."
        )
        showDialog(update)
    }

    private fun showDialog(update: BackendAppUpdate) {
        if (activity.isFinishing || activity.isDestroyed) return

        val title = if (update.forceUpdate) {
            activity.getString(R.string.update_required_title)
        } else {
            activity.getString(R.string.update_available_title)
        }
        val versionSuffix = update.latestVersionName
            .takeIf { it.isNotBlank() }
            ?.let { activity.getString(R.string.update_version_format, it) }
            .orEmpty()

        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(update.message + versionSuffix)
            .setPositiveButton(R.string.update_now) { _, _ ->
                openApk(update.apkUrl)
            }

        if (!update.forceUpdate) {
            builder.setNegativeButton(R.string.update_later, null)
        }

        updateDialog?.dismiss()
        val dialog = builder.create()
        updateDialog = dialog
        dialog.setCancelable(!update.forceUpdate)
        dialog.setCanceledOnTouchOutside(!update.forceUpdate)
        dialog.show()
    }

    private fun openApk(url: String) {
        if (!AppUpdateDestination.isValid(url)) {
            Toast.makeText(
                activity,
                activity.getString(R.string.update_invalid_link),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            )
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                activity.getString(R.string.update_open_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun currentVersionCode(): Long {
        val info = activity.packageManager.getPackageInfo(
            activity.packageName,
            0
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}
