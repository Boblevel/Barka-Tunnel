package com.barkatunnel.app.settings

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import com.barkatunnel.app.BuildConfig
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.networkinfo.NetworkIpProvider
import com.barkatunnel.app.update.AppUpdateCoordinator
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private val prefsName = "barka_settings"
    private lateinit var updateCoordinator: AppUpdateCoordinator
    private lateinit var settingsIpValue: TextView
    private var networkCallbackRegistered = false

    private val connectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshIpAsync()
        override fun onLost(network: Network) = refreshIpAsync()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshIpAsync()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshIpAsync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBars()
        updateCoordinator = AppUpdateCoordinator(this)

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        prefs.edit()
            .remove("auto_launch")
            .remove("auto_vpn")
            .remove("protocol")
            .remove("default_port")
            .apply()

        findViewById<TextView>(R.id.settingsVersionValue).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME)
        findViewById<TextView>(R.id.aboutValue).text =
            getString(R.string.settings_about_format, BuildConfig.VERSION_NAME)

        val notificationsSwitch = findViewById<SwitchCompat>(R.id.notificationsSwitch)
        val themeButton = findViewById<MaterialButton>(R.id.themeButton)
        val languageButton = findViewById<MaterialButton>(R.id.languageButton)
        settingsIpValue = findViewById(R.id.settingsIpValue)

        findViewById<android.view.View>(R.id.settingsBackButton).setOnClickListener {
            finish()
        }

        notificationsSwitch.isChecked = prefs.getBoolean("notifications", true)
        notificationsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notifications", checked).apply()
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }

        fun refreshDynamicLabels() {
            val currentTheme = normalizedTheme(
                prefs.getString("theme", "light") ?: "light"
            )
            themeButton.text = getString(
                R.string.settings_theme_format,
                themeLabel(currentTheme)
            )
            val language = prefs.getString("language", "fr") ?: "fr"
            languageButton.text = getString(
                R.string.settings_language_format,
                if (language == "en") {
                    getString(R.string.language_english)
                } else {
                    getString(R.string.language_french)
                }
            )
            val info = NetworkIpProvider.getCurrent(this)
            settingsIpValue.text = getString(
                R.string.settings_ip_format,
                info.ip,
                info.transport
            )
        }

        themeButton.setOnClickListener {
            showSingleChoiceDialog(
                title = getString(R.string.settings_choose_theme),
                values = arrayOf(
                    getString(R.string.theme_light),
                    getString(R.string.theme_dark),
                    getString(R.string.theme_system)
                ),
                selected = themeLabel(
                    normalizedTheme(prefs.getString("theme", "light") ?: "light")
                )
            ) { value ->
                val theme = when (value) {
                    getString(R.string.theme_dark) -> "dark"
                    getString(R.string.theme_system) -> "system"
                    else -> "light"
                }
                prefs.edit().putString("theme", theme).apply()
                val mode = when (theme) {
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> AppCompatDelegate.MODE_NIGHT_NO
                }
                AppCompatDelegate.setDefaultNightMode(mode)
                refreshDynamicLabels()
            }
        }

        languageButton.setOnClickListener {
            val french = getString(R.string.language_french)
            val english = getString(R.string.language_english)
            val currentLanguage = prefs.getString("language", "fr") ?: "fr"
            showSingleChoiceDialog(
                title = getString(R.string.settings_choose_language),
                values = arrayOf(french, english),
                selected = if (currentLanguage == "en") english else french
            ) { value ->
                val language = if (value == english) "en" else "fr"
                prefs.edit().putString("language", language).apply()
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(language)
                )
            }
        }

        refreshDynamicLabels()

        findViewById<MaterialButton>(R.id.vpnSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.appSettingsButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<MaterialButton>(R.id.checkUpdateButton).setOnClickListener {
            updateCoordinator.check(showNoUpdate = true, force = true)
        }

        findViewById<MaterialButton>(R.id.shareAppButton).setOnClickListener {
            shareApplication()
        }

        findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        findViewById<android.view.View>(R.id.navJournal).setOnClickListener {
            startActivity(
                Intent().setClassName(
                    packageName,
                    "com.barkatunnel.app.journal.JournalActivity"
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!networkCallbackRegistered) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    override fun onStop() {
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applySystemBars()
        refreshIpAsync()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001 && grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences(prefsName, MODE_PRIVATE)
                .edit()
                .putBoolean("notifications", false)
                .apply()
            findViewById<SwitchCompat>(R.id.notificationsSwitch).isChecked = false
            Toast.makeText(
                this,
                R.string.settings_notification_denied,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun refreshIpAsync() {
        if (!::settingsIpValue.isInitialized) return
        runOnUiThread {
            val info = NetworkIpProvider.getCurrent(this)
            settingsIpValue.text = getString(
                R.string.settings_ip_format,
                info.ip,
                info.transport
            )
        }
    }

    private fun shareApplication() {
        val apkUrl = "${BarkaBackendClient.BASE_URL}/downloads/BarkaTunnel.apk"
        val text = getString(R.string.share_download_format, apkUrl)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Barka Tunnel")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(
            Intent.createChooser(sendIntent, getString(R.string.share_app_chooser))
        )
    }

    private fun showSingleChoiceDialog(
        title: String,
        values: Array<String>,
        selected: String,
        onSelected: (String) -> Unit
    ) {
        var checkedIndex = values.indexOf(selected).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(values, checkedIndex) { _, which ->
                checkedIndex = which
            }
            .setPositiveButton(R.string.save) { _, _ ->
                onSelected(values[checkedIndex])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun normalizedTheme(value: String): String = when (value) {
        "dark", "Sombre" -> "dark"
        "system", "Système" -> "system"
        else -> "light"
    }

    private fun themeLabel(value: String): String = when (value) {
        "dark" -> getString(R.string.theme_dark)
        "system" -> getString(R.string.theme_system)
        else -> getString(R.string.theme_light)
    }

    private fun applySystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.barka_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.barka_background)
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val lightIcons = nightMode != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
    }
}
