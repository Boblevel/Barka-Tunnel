package com.barkatunnel.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.barkatunnel.app.BuildConfig
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.vpnprofile.VpnProfileRepository
import com.barkatunnel.app.vpnprofile.VpnProfileSyncResult
import java.util.concurrent.TimeUnit

class RemoteUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val backend = BarkaBackendClient(applicationContext)
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        return try {
            val catalog = backend.getVpnCatalog()
            val fingerprint = catalog
                .sortedBy { it.networkId }
                .joinToString("|") { item ->
                    listOf(
                        item.networkId,
                        item.version.toString(),
                        item.enabled.toString(),
                        item.maintenance.toString()
                    ).joinToString(":")
                }
            val previousFingerprint = prefs.getString(KEY_PROFILE_FINGERPRINT, null)

            if (previousFingerprint != null && previousFingerprint != fingerprint) {
                when (
                    VpnProfileRepository(backend, applicationContext).refreshCatalog()
                ) {
                    is VpnProfileSyncResult.Success -> {
                        showNotification(
                            NOTIFICATION_PROFILE_ID,
                            applicationContext.getString(R.string.remote_profile_update_title),
                            applicationContext.getString(R.string.remote_profile_update_message)
                        )
                    }

                    is VpnProfileSyncResult.Error -> return Result.retry()
                }
            }
            prefs.edit().putString(KEY_PROFILE_FINGERPRINT, fingerprint).apply()

            val appUpdate = backend.checkAppUpdate(BuildConfig.VERSION_CODE.toLong())
            val lastNotifiedVersion = prefs.getLong(
                KEY_LAST_NOTIFIED_APK_VERSION,
                BuildConfig.VERSION_CODE.toLong()
            )
            if (
                appUpdate.updateAvailable &&
                appUpdate.latestVersionCode > lastNotifiedVersion
            ) {
                showNotification(
                    NOTIFICATION_APK_ID,
                    applicationContext.getString(R.string.remote_apk_update_title),
                    appUpdate.message.ifBlank {
                        applicationContext.getString(R.string.update_available)
                    }
                )
                prefs.edit()
                    .putLong(KEY_LAST_NOTIFIED_APK_VERSION, appUpdate.latestVersionCode)
                    .apply()
            }

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(id: Int, title: String, message: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel()
        val openApp = PendingIntent.getActivity(
            applicationContext,
            id,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_barka)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(ContextCompat.getColor(applicationContext, R.color.barka_blue))
            .build()

        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.remote_update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = applicationContext.getString(R.string.remote_update_channel_description)
            }
        )
    }

    companion object {
        private const val PREFS_NAME = "barka_remote_update_notifications"
        private const val KEY_PROFILE_FINGERPRINT = "profile_fingerprint"
        private const val KEY_LAST_NOTIFIED_APK_VERSION = "last_notified_apk_version"
        private const val CHANNEL_ID = "barka_remote_updates"
        private const val NOTIFICATION_PROFILE_ID = 2201
        private const val NOTIFICATION_APK_ID = 2202
    }
}

object RemoteUpdateScheduler {
    private const val PERIODIC_WORK_NAME = "barka_remote_update_watch"
    private const val IMMEDIATE_WORK_NAME = "barka_remote_update_watch_now"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workManager = WorkManager.getInstance(context.applicationContext)

        val periodic = PeriodicWorkRequestBuilder<RemoteUpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        val immediate = OneTimeWorkRequestBuilder<RemoteUpdateWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediate
        )
    }
}
