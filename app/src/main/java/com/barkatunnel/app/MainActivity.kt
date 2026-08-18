package com.barkatunnel.app

import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.ui.home.NetworkOption
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var selectedNetwork: NetworkOption? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val networkSelector = findViewById<android.view.View>(R.id.networkSelector)
        val networkName = findViewById<TextView>(R.id.networkName)
        val networkSubtitle = findViewById<TextView>(R.id.networkSubtitle)
        val buttonFreeTrial = findViewById<android.view.View>(R.id.buttonFreeTrial)
        val buttonRefresh = findViewById<android.view.View>(R.id.buttonRefresh)
        val connectButton = findViewById<MaterialButton>(R.id.connectButton)
        val powerButton = findViewById<TextView>(R.id.powerButton)

        networkSelector.setOnClickListener {
            val options = NetworkOption.ALL
            val labels = options.map { it.displayName }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Choisir le réseau")
                .setItems(labels) { _, which ->
                    selectedNetwork = options[which]
                    networkName.text = options[which].displayName
                    networkSubtitle.text = "Réseau sélectionné"
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        buttonFreeTrial.setOnClickListener {
            Toast.makeText(
                this,
                "L’essai 1H sera activé uniquement après validation du serveur.",
                Toast.LENGTH_SHORT
            ).show()
        }

        buttonRefresh.setOnClickListener {
            Toast.makeText(
                this,
                "Actualisation des serveurs...",
                Toast.LENGTH_SHORT
            ).show()
        }

        val connectAction = {
            if (selectedNetwork == null) {
                Toast.makeText(
                    this,
                    "Choisis d’abord un réseau.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Connexion ${selectedNetwork!!.displayName} prête à être reliée au serveur.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        connectButton.setOnClickListener { connectAction() }
        powerButton.setOnClickListener { connectAction() }

        findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            Toast.makeText(this, "Accueil", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.view.View>(R.id.navIpFinder).setOnClickListener {
            Toast.makeText(this, "IP Finder arrive au prochain bloc.", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.view.View>(R.id.navJournal).setOnClickListener {
            Toast.makeText(this, "Journal arrive au prochain bloc.", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.view.View>(R.id.navSettings).setOnClickListener {
            Toast.makeText(this, "Paramètres arrivent au prochain bloc.", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.view.View>(R.id.buttonMenu).setOnClickListener {
            Toast.makeText(this, "Menu latéral arrive au prochain bloc.", Toast.LENGTH_SHORT).show()
        }
    }
}
