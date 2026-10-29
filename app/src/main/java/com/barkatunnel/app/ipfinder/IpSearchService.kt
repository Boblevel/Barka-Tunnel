package com.barkatunnel.app.ipfinder

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.ipfinder.assistant.BarkaAssistantService
import com.barkatunnel.app.networkinfo.NetworkIpProvider
import com.barkatunnel.app.journal.AppLogStore

/** User-started, cancellable search, independent of the activity lifecycle. */
class IpSearchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var criteria: IpSearchCriteria
    private var attempts = 0
    private var failures = 0
    private var waiting = false
    private var readyRetries = 0
    private var registered = false
    private lateinit var cm: ConnectivityManager
    private lateinit var notifications: NotificationManager
    private val stopAfterAlert = Runnable { stopSearchService() }
    private val check = Runnable { inspectIp() }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            handler.removeCallbacks(check); handler.postDelayed(check, 500)
        }
        override fun onAvailable(network: Network) {
            handler.removeCallbacks(check); handler.postDelayed(check, 500)
        }
    }
    private val completed = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active) return
            if (intent?.getBooleanExtra(BarkaAssistantService.EXTRA_CYCLE_SUCCEEDED, false) == true) {
                waiting = true; failures = 0; inspectIp()
                handler.postDelayed({ if (active && waiting) nextCycle() }, 30_000)
            } else if (++failures >= 2) finishSearch(getString(R.string.ip_finder_cycle_failed))
            else handler.postDelayed({ nextCycle() }, 5000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ip_finder_title), NotificationManager.IMPORTANCE_LOW))
            notifications.createNotificationChannel(NotificationChannel(FOUND_CHANNEL, getString(R.string.ip_finder_found), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern = FOUND_VIBRATION_PATTERN
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            })
        }
        ContextCompat.registerReceiver(this, completed, IntentFilter(BarkaAssistantService.ACTION_CYCLE_COMPLETED), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build(), callback)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finishSearch(getString(R.string.ip_finder_stopped)); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        handler.removeCallbacks(stopAfterAlert)
        criteria = IpFinderActivity.captureCriteria(this)
        active = true; foundIp = null; attempts = 0
        startForeground(NOTIFICATION, notification(CHANNEL, getString(R.string.ip_finder_searching), true))
        publish(getString(R.string.ip_finder_searching))
        handler.postDelayed({ nextCycle() }, 1000)
        return START_NOT_STICKY
    }
    private fun nextCycle() {
        if (!active) return
        handler.removeCallbacksAndMessages(null); waiting = false; readyRetries = 0
        val wifi = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (wifi) { finishSearch(getString(R.string.ip_finder_turn_off_wifi)); return }
        val ip = NetworkIpProvider.getCellularIpv4(this, includeVpn = false)
        if (ip != null && criteria.matches(ip)) { success(ip); return }
        attempts++
        publish(getString(R.string.ip_finder_attempt_format, attempts))
        requestCycle()
    }
    private fun requestCycle() {
        if (!active) return
        if (BarkaAssistantService.requestAirplaneCycle(this)) return
        if (!BarkaAssistantService.isSelected(this) || ++readyRetries >= 10) finishSearch(getString(R.string.ip_finder_cycle_failed))
        else handler.postDelayed({ requestCycle() }, 1000)
    }
    private fun inspectIp() {
        if (!active || !waiting) return
        val ip = NetworkIpProvider.getCellularIpv4(this, includeVpn = false) ?: return
        if (criteria.matches(ip)) success(ip)
        else {
            waiting = false; handler.removeCallbacksAndMessages(null)
            // Give the radio a rest between unsuccessful attempts.
            handler.postDelayed({ nextCycle() }, minOf(10_000L, 3000L + attempts * 500L))
        }
    }
    private fun success(ip: String) {
        if (!active) return
        foundIp = ip
        getSharedPreferences(IpFinderActivity.PREFERENCES_NAME, MODE_PRIVATE).edit()
            .putString(IpFinderActivity.KEY_LAST_FOUND_IP, ip).apply()
        val message = getString(R.string.ip_finder_found_detail, ip)
        AppLogStore.add(this, getString(R.string.ip_finder_log_found_format, ip))
        runCatching { notifications.notify(FOUND_NOTIFICATION, notification(FOUND_CHANNEL, message, false)) }
            .onFailure { AppLogStore.add(this, getString(R.string.ip_alert_notification_failed, it.javaClass.simpleName)) }
        val vibrationRequested = vibrateFound()
        // Stop searching immediately, but keep the foreground service alive until the alert ends.
        finishSearch(message, if (vibrationRequested) FOUND_VIBRATION_PATTERN.sum() + 250L else 0L)
    }
    @Suppress("DEPRECATION")
    private fun vibrateFound(): Boolean {
        // A notification usage is required for vibration while the activity is hidden.
        fun blocked(reason: Int): Boolean {
            AppLogStore.add(this, getString(reason))
            return false
        }
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled())
            return blocked(R.string.ip_alert_notifications_disabled)
        if (notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
            return blocked(R.string.ip_alert_dnd)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = notifications.getNotificationChannel(FOUND_CHANNEL)
                ?: return blocked(R.string.ip_alert_channel_disabled)
            if (!channel.shouldVibrate() || channel.importance < NotificationManager.IMPORTANCE_DEFAULT)
                return blocked(R.string.ip_alert_channel_disabled)
        }
        val vibrator = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        else getSystemService(Vibrator::class.java)
        if (vibrator?.hasVibrator() != true) return blocked(R.string.ip_alert_no_vibrator)
        val pattern = FOUND_VIBRATION_PATTERN
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1),
                    VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_NOTIFICATION).build())
            } else {
                val attributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
                else vibrator.vibrate(pattern, -1, attributes)
            }
            AppLogStore.add(this, getString(R.string.ip_alert_requested))
            true
        }.getOrElse {
            AppLogStore.add(this, getString(R.string.ip_alert_failed, it.javaClass.simpleName))
            false
        }
    }

    private fun notification(channel: String, message: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, IpFinderActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, IpSearchService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_menu_ip_finder)
            .setContentTitle(getString(if (ongoing) R.string.ip_finder_searching else R.string.ip_finder_found))
            .setContentText(message).setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing)
            .setPriority(if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (ongoing) { setSilent(true); addAction(0, getString(R.string.ip_finder_stop), stop) }
                else setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE) }.build()
    }
    private fun publish(message: String) {
        status = message
        sendBroadcast(Intent(CHANGED).setPackage(packageName))
        if (active) notifications.notify(NOTIFICATION, notification(CHANNEL, message, true))
    }
    private fun finishSearch(message: String, alertDurationMs: Long = 0L) {
        active = false; handler.removeCallbacksAndMessages(null)
        BarkaAssistantService.cancelAirplaneCycle(this)
        publish(message)
        if (alertDurationMs > 0L) handler.postDelayed(stopAfterAlert, alertDurationMs)
        else stopSearchService()
    }
    private fun stopSearchService() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else { @Suppress("DEPRECATION") stopForeground(true) }
        stopSelf()
    }
    override fun onDestroy() {
        val interrupted = active
        active = false; handler.removeCallbacksAndMessages(null)
        BarkaAssistantService.cancelAirplaneCycle(this)
        if (interrupted) publish(getString(R.string.ip_finder_stopped))
        if (registered) unregisterReceiver(completed)
        runCatching { cm.unregisterNetworkCallback(callback) }
        super.onDestroy()
    }
    companion object {
        const val CHANGED = "com.barkatunnel.app.IP_SEARCH_CHANGED"
        const val STOP = "com.barkatunnel.app.IP_SEARCH_STOP"
        private const val CHANNEL = "ip_search_progress"
        private const val FOUND_CHANNEL = "ip_search_found"
        private const val NOTIFICATION = 740
        private const val FOUND_NOTIFICATION = 741
        private val FOUND_VIBRATION_PATTERN = longArrayOf(0, 400, 150, 400)
        @Volatile var active = false; private set
        @Volatile var status = ""; private set
        @Volatile var foundIp: String? = null; private set
    }
}
