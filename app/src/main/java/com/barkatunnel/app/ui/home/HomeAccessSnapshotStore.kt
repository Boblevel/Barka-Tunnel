package com.barkatunnel.app.ui.home

import android.content.Context

object HomeAccessSnapshotStore {
    private const val PREFS = "barka_access_snapshot"
    private const val KEY_EXPIRY_MS = "expiry_ms"
    private const val KEY_ALLOWED = "allowed"

    fun save(context: Context, state: HomeAccessState) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!state.allowed || state.remainingSeconds <= 0L) {
            prefs.edit().clear().apply()
            return
        }
        val expiry = System.currentTimeMillis() + (state.remainingSeconds * 1000L)
        prefs.edit()
            .putBoolean(KEY_ALLOWED, true)
            .putLong(KEY_EXPIRY_MS, expiry)
            .apply()
    }

    fun restore(context: Context): HomeAccessState? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ALLOWED, false)) return null
        val expiry = prefs.getLong(KEY_EXPIRY_MS, 0L)
        val remaining = ((expiry - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        if (remaining <= 0L) {
            prefs.edit().clear().apply()
            return null
        }
        return HomeAccessState(
            allowed = true,
            remainingSeconds = remaining,
            label = "Accès actif • hors ligne"
        )
    }
}
