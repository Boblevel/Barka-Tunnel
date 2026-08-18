package com.barkatunnel.app.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.networkinfo.NetworkIpProvider
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private val prefsName = "barka_settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        )

        val notificationsSwitch =
            findViewById<SwitchCompat>(R.id.notificationsSwitch)

        notificationsSwitch.isChecked =
            prefs.getBoolean("notifications", true)

        notificationsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit()
                .putBoolean("notifications", checked)
                .apply()
        }

        findViewById<TextView>(R.id.settingsIpValue).text =
            NetworkIpProvider.getCurrent(this).ip

        findViewById<MaterialButton>(R.id.airplaneSettingsButton).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                )
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
            AppLogStore.add(
                this,
                "Informations • journal réinitialisé depuis Paramètres."
            )
            Toast.makeText(
                this,
                "Journal vidé.",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<MaterialButton>(R.id.checkUpdateButton).setOnClickListener {
            Toast.makeText(
                this,
                "Barka Tunnel est prêt pour la vérification serveur des mises à jour.",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<MaterialButton>(R.id.supportButton).setOnClickListener {
            try {
                val uri = Uri.parse("https://wa.me/message/XUBALKJE5J2CB1")
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Impossible d’ouvrir le support.",
                    Toast.LENGTH_SHORT
                ).show()
            }
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

    override fun onResume() {
        super.onResume()

        findViewById<TextView>(R.id.settingsIpValue).text =
            NetworkIpProvider.getCurrent(this).ip
    }
}
