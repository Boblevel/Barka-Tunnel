package com.barkatunnel.app

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.barkatunnel.app.core.AppContainer
import com.barkatunnel.app.update.RemoteUpdateScheduler
import com.barkatunnel.app.ui.common.PressFeedback

class BarkaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Le processus privé du pont VPN ne doit initialiser ni l'interface,
        // ni le planificateur de mises à jour de l'application principale.
        if (currentProcessName()?.endsWith(":tun2socks") == true) return

        // One migration for this release; later user choices remain untouched.
        val settings = getSharedPreferences("barka_settings", MODE_PRIVATE)
        if (!settings.getBoolean("auto_ping_default_105", false)) {
            settings.edit().putBoolean("auto_ping", true)
                .putBoolean("auto_ping_default_105", true).commit()
        }
        registerActivityLifecycleCallbacks(PressFeedback)
        applySavedLanguage()
        applySavedTheme()
        container = AppContainer(this)
        RemoteUpdateScheduler.schedule(this)
    }

    @Suppress("DEPRECATION")
    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        val pid = Process.myPid()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }

    private fun applySavedLanguage() {
        val language = getSharedPreferences("barka_settings", MODE_PRIVATE)
            .getString("language", "fr")
            .takeUnless { it.isNullOrBlank() }
            ?: "fr"
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language)
        )
    }

    private fun applySavedTheme() {
        val theme = getSharedPreferences("barka_settings", MODE_PRIVATE)
            .getString("theme", "light")
            ?: "light"

        val mode = when (theme) {
            "dark", "Sombre" -> AppCompatDelegate.MODE_NIGHT_YES
            "system", "Système" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }

        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
