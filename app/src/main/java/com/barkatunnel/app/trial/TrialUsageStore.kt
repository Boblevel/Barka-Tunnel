package com.barkatunnel.app.trial

import android.content.Context
import com.barkatunnel.app.device.DeviceIdentity

object TrialUsageStore {
    private const val PREFERENCES = "barka_trial_usage"

    fun wasUsed(context: Context): Boolean? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = DeviceIdentity.getDeviceId(context)
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }

    @Synchronized
    fun record(context: Context, used: Boolean) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = DeviceIdentity.getDeviceId(context)
        // Une réponse ancienne ne doit jamais réafficher un cadeau déjà utilisé.
        if (preferences.getBoolean(key, false)) return
        preferences.edit().putBoolean(key, used).commit()
    }
}
