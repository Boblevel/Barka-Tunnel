package com.barkatunnel.app.ipfinder

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore
import com.google.android.material.button.MaterialButton
import java.net.InetSocketAddress
import java.net.Socket

class IpFinderActivity : AppCompatActivity() {

    @Volatile
    private var scanning = false

    private var lastFoundIp: String? = null
    private var lastFoundPort: Int? = null
    private var lastFoundLatency: Long? = null

    private lateinit var scanButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var resultText: TextView
    private lateinit var copyButton: MaterialButton
    private lateinit var useButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ip_finder)

        val ipInput = findViewById<EditText>(R.id.ipInput)
        val portsInput = findViewById<EditText>(R.id.portsInput)

        val standardPorts = listOf("443", "80", "8080", "53")
            .joinToString(", ")
        portsInput.setText(standardPorts)

        scanButton = findViewById(R.id.scanButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.scanStatus)
        progressText = findViewById(R.id.scanProgress)
        resultText = findViewById(R.id.scanResult)
        copyButton = findViewById(R.id.copyIpButton)
        useButton = findViewById(R.id.useIpButton)

        findViewById<android.view.View>(R.id.backButton).setOnClickListener {
            finish()
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

        copyButton.setOnClickListener {
            val ip = lastFoundIp ?: return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("IP Barka Tunnel", ip)
            )
            Toast.makeText(this, "IP copiée.", Toast.LENGTH_SHORT).show()
        }

        useButton.setOnClickListener {
            val ip = lastFoundIp ?: return@setOnClickListener
            getSharedPreferences("barka_ipfinder", Context.MODE_PRIVATE)
                .edit()
                .putString("selected_ip", ip)
                .putInt("selected_port", lastFoundPort ?: 0)
                .apply()

            AppLogStore.add(
                this,
                "IP Finder • IP sélectionnée : $ip:${lastFoundPort ?: 0}."
            )

            Toast.makeText(
                this,
                "IP sélectionnée pour Barka Tunnel.",
                Toast.LENGTH_SHORT
            ).show()
        }

        stopButton.setOnClickListener {
            stopScan("Scan arrêté manuellement.")
        }

        scanButton.setOnClickListener {
            if (scanning) {
                stopScan("Scan arrêté manuellement.")
                return@setOnClickListener
            }

            val ips = ipInput.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .filter { isValidIpv4(it) }

            if (ips.isEmpty()) {
                Toast.makeText(
                    this,
                    "Ajoute au moins une IPv4 valide.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val ports = parsePorts(portsInput.text.toString())

            if (ports.isEmpty()) {
                Toast.makeText(
                    this,
                    "Ajoute au moins un port valide.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            startScan(ips, ports)
        }
    }

    override fun onDestroy() {
        scanning = false
        super.onDestroy()
    }

    private fun startScan(
        ips: List<String>,
        ports: List<Int>
    ) {
        scanning = true
        lastFoundIp = null
        lastFoundPort = null
        lastFoundLatency = null

        scanButton.text = "SCAN EN COURS…"
        scanButton.isEnabled = false
        stopButton.isEnabled = true
        copyButton.isEnabled = false
        useButton.isEnabled = false

        statusText.text = "Scan en cours…"
        progressText.text = "Testées : 0/${ips.size} • Réussies : 0"
        resultText.text = "Recherche de la première IP répondante…"

        AppLogStore.add(
            this,
            "IP Finder • scan lancé sur ${ips.size} IP • ports ${ports.joinToString(",")}."
        )

        Thread {
            var found: TestResult? = null

            for ((index, ip) in ips.withIndex()) {
                if (!scanning) break

                runOnUiThread {
                    progressText.text =
                        "Testées : ${index + 1}/${ips.size} • En cours : $ip"
                }

                found = testIp(ip, ports)

                if (found != null) {
                    break
                }
            }

            val completedNormally = scanning
            scanning = false

            runOnUiThread {
                scanButton.text = "DÉMARRER LE SCAN"
                scanButton.isEnabled = true
                stopButton.isEnabled = false

                if (found != null) {
                    val hit = found!!
                    lastFoundIp = hit.ip
                    lastFoundPort = hit.port
                    lastFoundLatency = hit.latencyMs

                    statusText.text = "IP trouvée !"
                    progressText.text = "Réussies : 1 • Scan arrêté automatiquement"
                    resultText.text =
                        "${hit.ip}\\nPort : ${hit.port}\\nRéponse : ${hit.latencyMs} ms"

                    copyButton.isEnabled = true
                    useButton.isEnabled = true

                    AppLogStore.add(
                        this,
                        "Succès IP Finder • ${hit.ip}:${hit.port} • ${hit.latencyMs} ms."
                    )
                } else if (completedNormally) {
                    statusText.text = "Scan terminé"
                    progressText.text = "Réussies : 0"
                    resultText.text = "Aucune IP répondante trouvée."
                    AppLogStore.add(
                        this,
                        "IP Finder • aucune IP répondante trouvée."
                    )
                }
            }
        }.start()
    }

    private fun stopScan(message: String) {
        scanning = false
        scanButton.text = "DÉMARRER LE SCAN"
        scanButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = message
        AppLogStore.add(this, "IP Finder • $message")
    }

    private fun testIp(
        ip: String,
        ports: List<Int>
    ): TestResult? {
        for (port in ports) {
            if (!scanning) return null

            val startedAt = System.currentTimeMillis()

            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(ip, port),
                        850
                    )

                    return TestResult(
                        ip = ip,
                        port = port,
                        latencyMs = System.currentTimeMillis() - startedAt
                    )
                }
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun parsePorts(value: String): List<Int> {
        return value
            .split(",", " ", "\\n", ";")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .distinct()
            .ifEmpty { listOf(443, 80, 8080, 53) }
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private data class TestResult(
        val ip: String,
        val port: Int,
        val latencyMs: Long
    )
}
