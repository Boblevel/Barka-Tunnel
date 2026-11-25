package com.barkatunnel.app.ipfinder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.barkatunnel.app.R

/** Advisory only: never changes system settings or gates the search. */
internal class IpVibrationSettings(private val activity: Activity) {
    internal enum class State { DISABLED, UNKNOWN, AVAILABLE, NO_HARDWARE }
    private var shownState: State? = null
    private var dialog: AlertDialog? = null

    internal fun state(): State {
        val hardware = runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31)
                activity.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else activity.getSystemService(Vibrator::class.java)
            vibrator?.hasVibrator()
        }.getOrNull()
        if (hardware == false) return State.NO_HARDWARE
        // These read-only AOSP keys may be missing or inaccessible on OEM devices.
        // Missing values must never be reported as disabled (or definitely enabled).
        fun read(key: String): Int? = runCatching {
            Settings.System.getInt(activity.contentResolver, key)
        }.getOrNull()
        val global = read("vibrate_on")
        @Suppress("DEPRECATION")
        val touch = read(Settings.System.HAPTIC_FEEDBACK_ENABLED)
        val intensity = read("haptic_feedback_intensity")
        if (global == 0 || touch == 0 || intensity == 0) return State.DISABLED
        return if (hardware == true && global == 1 && touch == 1 && intensity in 1..3)
            State.AVAILABLE else State.UNKNOWN
    }

    fun offerIfNeeded() {
        if (activity.isFinishing || activity.isDestroyed || dialog?.isShowing == true) return
        val current = state()
        if (current == State.AVAILABLE) { shownState = null; return }
        if (current == shownState) return
        if (current == State.UNKNOWN && shownState != null) return
        val prefs = activity.getSharedPreferences("ip_vibration_help", Context.MODE_PRIVATE)
        if (current == State.UNKNOWN && prefs.getBoolean("unknown_explained", false)) return
        shownState = current
        if (current == State.UNKNOWN) prefs.edit().putBoolean("unknown_explained", true).apply()
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.ip_vibration_settings_title)
            .setMessage(when (current) {
                State.DISABLED -> R.string.ip_vibration_settings_disabled
                State.NO_HARDWARE -> R.string.ip_vibration_settings_no_hardware
                else -> R.string.ip_vibration_settings_unknown
            })
            .setNegativeButton(R.string.ip_vibration_continue, null)
        if (current != State.NO_HARDWARE) builder.setPositiveButton(R.string.ip_vibration_open_settings) { _, _ ->
            // Public settings screens, with fallbacks for manufacturer differences.
            val opened = listOf(Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_ACCESSIBILITY_SETTINGS,
                Settings.ACTION_SETTINGS).any { action ->
                runCatching { activity.startActivity(Intent(action)); true }.getOrDefault(false)
            }
            if (!opened) Toast.makeText(activity, R.string.ip_vibration_settings_manual, Toast.LENGTH_LONG).show()
        }
        dialog = builder.create().also { it.show() }
    }

    fun close() { dialog?.dismiss(); dialog = null }
}
