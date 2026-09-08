package com.barkatunnel.app.trial

import android.content.Context
import android.os.SystemClock
import com.barkatunnel.app.device.DeviceIdentity

object TrialUsageStore {
    private const val PREFERENCES = "barka_weekly_trial_usage"
    @Volatile private var refreshAtElapsedMs = 0L

    fun refreshDelayMillis(): Long =
        (refreshAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(1_000L)

    fun wasUsed(context: Context): Boolean? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = DeviceIdentity.getDeviceId(context)
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }

    @Synchronized
    fun record(context: Context, used: Boolean, week: Long, serverTime: Long) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = DeviceIdentity.getDeviceId(context)
        val previousWeek = preferences.getLong("$key:week", -1L)
        if (week < previousWeek) return
        // Une ancienne réponse de la même semaine ne réaffiche pas un cadeau utilisé.
        val effectiveUsed = used || (week == previousWeek && preferences.getBoolean(key, false))
        preferences.edit().putLong("$key:week", week).putBoolean(key, effectiveUsed).commit()
        // Horloge monotone : changer la date du téléphone ne renouvelle aucun droit.
        refreshAtElapsedMs = SystemClock.elapsedRealtime() +
            ((week + 604_800L - serverTime).coerceIn(1L, 604_800L) * 1000L)
    }
}
