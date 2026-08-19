package com.barkatunnel.app.update

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    fun check(showNoUpdate: Boolean = false, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (checking) return
        if (!force && now - lastCheckAt < 5 * 60 * 1000L) return

        checking = true
        Thread {
            try {
                val update = backendClient.checkAppUpdate(currentVersionCode())
                lastCheckAt = System.currentTimeMillis()
                activity.runOnUiThread {
                    applyResult(update, showNoUpdate)
                }
            } catch (e: Exception) {
                if (showNoUpdate) {
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            e.message ?: "Impossible de vérifier les mises à jour.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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
                    "Barka Tunnel est à jour.",
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
            "Mise à jour obligatoire"
        } else {
            "Mise à jour disponible"
        }
        val versionSuffix = update.latestVersionName
            .takeIf { it.isNotBlank() }
            ?.let { "\n\nVersion : $it" }
            .orEmpty()

        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(update.message + versionSuffix)
            .setPositiveButton("METTRE À JOUR") { _, _ ->
                openApk(update.apkUrl)
            }

        if (!update.forceUpdate) {
            builder.setNegativeButton("PLUS TARD", null)
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
                "Lien de mise à jour invalide.",
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
                "Impossible d'ouvrir la mise à jour.",
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
