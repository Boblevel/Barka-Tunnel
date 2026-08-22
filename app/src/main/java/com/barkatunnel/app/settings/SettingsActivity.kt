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
import androidx.core.view.WindowCompat
import com.barkatunnel.app.BuildConfig
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.networkinfo.NetworkIpProvider
import com.barkatunnel.app.support.SupportActivity
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

        findViewById<TextView>(R.id.settingsVersionValue).text = "Version ${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.aboutValue).text = "Barka Tunnel\nVersion ${BuildConfig.VERSION_NAME}"

        val notificationsSwitch = findViewById<SwitchCompat>(R.id.notificationsSwitch)
        val autoLaunchSwitch = findViewById<SwitchCompat>(R.id.autoLaunchSwitch)
        val autoVpnSwitch = findViewById<SwitchCompat>(R.id.autoVpnSwitch)
        val themeButton = findViewById<MaterialButton>(R.id.themeButton)
        val protocolButton = findViewById<MaterialButton>(R.id.protocolButton)
        val portButton = findViewById<MaterialButton>(R.id.portButton)
        settingsIpValue = findViewById(R.id.settingsIpValue)

        findViewById<android.view.View>(R.id.settingsBackButton).setOnClickListener {
            finish()
        }

        notificationsSwitch.isChecked = prefs.getBoolean("notifications", true)
        autoLaunchSwitch.isChecked = prefs.getBoolean("auto_launch", false)
        autoVpnSwitch.isChecked = prefs.getBoolean("auto_vpn", false)

        notificationsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notifications", checked).apply()
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }
        autoLaunchSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_launch", checked).apply()
            Toast.makeText(
                this,
                if (checked) "Démarrage automatique activé." else "Démarrage automatique désactivé.",
                Toast.LENGTH_SHORT
            ).show()
        }
        autoVpnSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_vpn", checked).apply()
            Toast.makeText(
                this,
                if (checked) "Connexion VPN automatique activée." else "Connexion VPN automatique désactivée.",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun refreshDynamicLabels() {
            val currentTheme = prefs.getString("theme", "Clair") ?: "Clair"
            val currentProtocol = prefs.getString("protocol", "Auto") ?: "Auto"
            val currentPort = prefs.getString("default_port", "443") ?: "443"
            themeButton.text = "Thème • $currentTheme"
            protocolButton.text = "Protocole • $currentProtocol"
            portButton.text = "Port par défaut • $currentPort"
            settingsIpValue.text = "Adresse IP : ${NetworkIpProvider.getCurrent(this).ip}"
        }

        themeButton.setOnClickListener {
            showSingleChoiceDialog(
                title = "Choisir le thème",
                values = arrayOf("Clair", "Sombre", "Système"),
                selected = prefs.getString("theme", "Clair") ?: "Clair"
            ) { value ->
                prefs.edit().putString("theme", value).apply()
                val mode = when (value) {
                    "Sombre" -> AppCompatDelegate.MODE_NIGHT_YES
                    "Système" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> AppCompatDelegate.MODE_NIGHT_NO
                }
                AppCompatDelegate.setDefaultNightMode(mode)
                refreshDynamicLabels()
            }
        }

        protocolButton.setOnClickListener {
            showSingleChoiceDialog(
                title = "Choisir le protocole",
                values = arrayOf("Auto", "SlowDNS", "VLESS", "UDP"),
                selected = prefs.getString("protocol", "Auto") ?: "Auto"
            ) { value ->
                prefs.edit().putString("protocol", value).apply()
                refreshDynamicLabels()
                Toast.makeText(this, "Préférence enregistrée : $value", Toast.LENGTH_SHORT).show()
            }
        }

        portButton.setOnClickListener {
            showSingleChoiceDialog(
                title = "Choisir le port par défaut",
                values = arrayOf("443", "80", "8080", "53"),
                selected = prefs.getString("default_port", "443") ?: "443"
            ) { value ->
                prefs.edit().putString("default_port", value).apply()
                refreshDynamicLabels()
            }
        }

        refreshDynamicLabels()

        findViewById<MaterialButton>(R.id.airplaneSettingsButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

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

        findViewById<MaterialButton>(R.id.clearLogsButton).setOnClickListener {
            AppLogStore.clear(this)
            AppLogStore.add(this, "Informations • journal réinitialisé depuis Paramètres.")
            Toast.makeText(this, "Journal vidé.", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.checkUpdateButton).setOnClickListener {
            updateCoordinator.check(showNoUpdate = true, force = true)
        }

        findViewById<MaterialButton>(R.id.supportButton).setOnClickListener {
            startActivity(Intent(this, SupportActivity::class.java))
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
            Toast.makeText(this, "Notifications non autorisées.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshIpAsync() {
        if (!::settingsIpValue.isInitialized) return
        runOnUiThread {
            val info = NetworkIpProvider.getCurrent(this)
            settingsIpValue.text = "Adresse IP : ${info.ip} • ${info.transport}"
        }
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
            .setPositiveButton("ENREGISTRER") { _, _ ->
                onSelected(values[checkedIndex])
            }
            .setNegativeButton("ANNULER", null)
            .show()
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
