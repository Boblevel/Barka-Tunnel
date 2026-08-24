package com.barkatunnel.app.journal

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogStore {

    private const val PREFS = "barka_journal"
    private const val KEY_LOGS = "logs"
    private const val MAX_LINES = 250

    fun add(
        context: Context,
        message: String
    ) {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val formatter = SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        )

        val line = "[${formatter.format(Date())}] $message"

        val current = prefs
            .getString(KEY_LOGS, "")
            .orEmpty()
            .lines()
            .filter { it.isNotBlank() }
            .toMutableList()

        current.add(line)

        prefs.edit()
            .putString(
                KEY_LOGS,
                current.takeLast(MAX_LINES).joinToString("\n")
            )
            .apply()
    }

    fun getAll(context: Context): String {
        return context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(KEY_LOGS, "")
            .orEmpty()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(KEY_LOGS)
            .apply()
    }

    fun registerChangeListener(
        context: Context,
        onChanged: () -> Unit
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LOGS || key == null) onChanged()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(listener)
    }
}
