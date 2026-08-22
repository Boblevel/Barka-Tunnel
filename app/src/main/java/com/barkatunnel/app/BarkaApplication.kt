package com.barkatunnel.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.barkatunnel.app.core.AppContainer

class BarkaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        applySavedTheme()
        container = AppContainer(this)
    }

    private fun applySavedTheme() {
        val theme = getSharedPreferences("barka_settings", MODE_PRIVATE)
            .getString("theme", "Clair")
            ?: "Clair"

        val mode = when (theme) {
            "Sombre" -> AppCompatDelegate.MODE_NIGHT_YES
            "Système" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }

        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
