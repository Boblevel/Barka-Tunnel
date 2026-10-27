package com.barkatunnel.app.ui.home

import android.content.Context

object HomeAccessSnapshotStore {
    private const val PREFS = "barka_access_snapshot"
    private const val KEY_EXPIRY_MS = "expiry_ms"
    private const val KEY_ALLOWED = "allowed"

    @Volatile var activationGeneration = 0L
        private set
    @Synchronized fun saveActivation(context: Context, state: HomeAccessState) {
        save(context, state)
        activationGeneration++
    }
    @Synchronized fun saveRefresh(context: Context, state: HomeAccessState, generation: Long): HomeAccessState {
        if (generation != activationGeneration) return restore(context) ?: state
        save(context, state)
        return state
    }
    private fun boot(context: Context): Int = if (android.os.Build.VERSION.SDK_INT >= 24)
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) else -1

    @Synchronized fun save(context: Context, state: HomeAccessState) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!state.allowed || state.remainingSeconds <= 0L) {
            prefs.edit().clear().commit()
            return
        }
        val expiry = System.currentTimeMillis() + (state.remainingSeconds * 1000L)
        prefs.edit()
            .putBoolean(KEY_ALLOWED, true)
            .putBoolean("unlimited", state.remainingSeconds == ConnectionTimeFormatter.UNLIMITED_SECONDS)
            .putLong(KEY_EXPIRY_MS, expiry)
            .putLong("remaining_seconds", state.remainingSeconds)
            .putLong("saved_elapsed", android.os.SystemClock.elapsedRealtime())
            .putInt("saved_boot", boot(context))
            .commit()
    }

    @Synchronized fun restore(context: Context): HomeAccessState? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ALLOWED, false)) return null
        val expiry = prefs.getLong(KEY_EXPIRY_MS, 0L)
        val elapsed = android.os.SystemClock.elapsedRealtime() - prefs.getLong("saved_elapsed", -1L)
        val sameBoot = prefs.contains("saved_elapsed") && elapsed >= 0 && boot(context) >= 0 && prefs.getInt("saved_boot", -2) == boot(context)
        val remaining = if (prefs.getBoolean("unlimited", false)) ConnectionTimeFormatter.UNLIMITED_SECONDS
            else if (sameBoot) (prefs.getLong("remaining_seconds", 0L) - elapsed / 1000L).coerceAtLeast(0L)
            else ((expiry - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        if (remaining <= 0L) {
            prefs.edit().clear().commit()
            return null
        }
        return HomeAccessState(
            allowed = true,
            remainingSeconds = remaining,
            label = "Accès actif • hors ligne"
        )
    }
}
