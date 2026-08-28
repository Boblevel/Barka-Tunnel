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

    @Volatile
    private var lastCheckAt = 0L

    fun check(
        showNoUpdate: Boolean = false,
        force: Boolean = false,
        onResult: ((BackendAppUpdate?) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        if (checking) {
            onResult?.invoke(null)
            return
        }
        if (!force && now - lastCheckAt < 5 * 60 * 1000L) {
            onResult?.invoke(null)
            return
        }

        checking = true
        Thread {
            try {
                val update = backendClient.checkAppUpdate(currentVersionCode())
                lastCheckAt = System.currentTimeMillis()
                activity.runOnUiThread {
                    applyResult(update, showNoUpdate)
                    onResult?.invoke(update)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (showNoUpdate) {
                        Toast.makeText(
                            activity,
                            e.message ?: activity.getString(R.string.update_check_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onResult?.invoke(null)
                }
            } finally {
                checking = false
            }
        }.start()
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
        if (!update.updateAvailable) {
            AppUpdateGate.clear()
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

        val dialog = builder.create()
        dialog.setCancelable(!update.forceUpdate)
        dialog.setCanceledOnTouchOutside(!update.forceUpdate)
        dialog.show()
    }

    private fun openApk(url: String) {
        if (!url.startsWith("https://")) {
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
