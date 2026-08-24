package com.barkatunnel.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.barkatunnel.app.core.AppContainer

class BarkaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        applySavedLanguage()
        applySavedTheme()
        container = AppContainer(this)
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
