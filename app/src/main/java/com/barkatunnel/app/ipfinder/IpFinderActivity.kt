package com.barkatunnel.app.ipfinder

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton
import java.net.InetSocketAddress
import java.net.Socket

class IpFinderActivity : AppCompatActivity() {

    @Volatile
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ip_finder)

        val input = findViewById<EditText>(R.id.ipInput)
        val result = findViewById<TextView>(R.id.scanResult)
        val scanButton = findViewById<MaterialButton>(R.id.scanButton)
        val airplaneButton = findViewById<MaterialButton>(R.id.airplaneSettingsButton)

        airplaneButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        scanButton.setOnClickListener {
            if (scanning) {
                scanning = false
                scanButton.text = "LANCER LE SCAN"
                result.text = "Scan arrêté."
                return@setOnClickListener
            }

            val ips = input.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            if (ips.isEmpty()) {
                Toast.makeText(this, "Ajoute au moins une IP.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            scanning = true
            scanButton.text = "ARRÊTER LE SCAN"
            result.text = "Recherche en cours…"

            Thread {
                var foundIp: String? = null
                var foundPing: Long? = null

                for ((index, ip) in ips.withIndex()) {
                    if (!scanning) break

                    runOnUiThread {
                        result.text = "Test ${index + 1}/${ips.size} : $ip"
                    }

                    val start = System.currentTimeMillis()
                    val ok = testIp(ip)
                    val elapsed = System.currentTimeMillis() - start

                    if (ok) {
                        foundIp = ip
                        foundPing = elapsed
                        break
                    }
                }

                scanning = false

                runOnUiThread {
                    scanButton.text = "LANCER LE SCAN"

                    if (foundIp != null) {
                        result.text = "IP trouvée : $foundIp\nTemps de réponse : ${foundPing} ms\nLe scan s'est arrêté automatiquement."
                    } else {
                        result.text = "Aucune IP répondante trouvée."
                    }
                }
            }.start()
        }
    }

    private fun testIp(ip: String): Boolean {
        val ports = intArrayOf(443, 80)

        for (port in ports) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 1200)
                    return true
                }
            } catch (_: Exception) {
            }
        }

        return false
    }
}
