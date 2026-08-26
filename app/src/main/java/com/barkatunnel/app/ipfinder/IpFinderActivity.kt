package com.barkatunnel.app.ipfinder

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.ipfinder.assistant.BarkaAssistantService
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.vpnc6.BarkaVpnService
import com.google.android.material.button.MaterialButton
import java.net.Inet4Address

class IpFinderActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Volatile private var searching = false
    private var receiverRegistered = false
    private var attempts = 0
    private var cycleRequestRetries = 0
    private var previousIp: String? = null
    private var searchPatterns: List<String> = emptyList()

    private lateinit var scanButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var setAssistantButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var resultText: TextView
    private lateinit var wifiWarning: TextView
    private lateinit var ipInput: EditText

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshAssistantState()
        if (!BarkaAssistantService.isSelected(this)) {
            openAssistantSettingsFallback()
        }
    }

    private val cycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action != BarkaAssistantService.ACTION_CYCLE_COMPLETED ||
                !searching
            ) {
                return
            }
            val succeeded = intent.getBooleanExtra(
                BarkaAssistantService.EXTRA_CYCLE_SUCCEEDED,
                false
            )
            if (succeeded) {
                waitForCellularIp(0)
            } else if (BarkaAssistantService.isSelected(this@IpFinderActivity)) {
                handler.postDelayed({ requestNextCycle() }, NEXT_CYCLE_DELAY_MS)
            } else {
                stopSearch(
                    getString(R.string.ip_finder_cycle_failed),
                    R.color.barka_red
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ip_finder)

        ipInput = findViewById(R.id.ipInput)
        scanButton = findViewById(R.id.scanButton)
        stopButton = findViewById(R.id.stopButton)
        setAssistantButton = findViewById(R.id.setAssistantButton)
        statusText = findViewById(R.id.scanStatus)
        progressText = findViewById(R.id.scanProgress)
        resultText = findViewById(R.id.scanResult)
        wifiWarning = findViewById(R.id.wifiWarning)

        val savedSearchPattern = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SEARCH_PATTERN, "")
            .orEmpty()
        ipInput.setText(savedSearchPattern.ifBlank { DEFAULT_SEARCH_PATTERN })

        findViewById<android.view.View>(R.id.backButton).setOnClickListener {
            finish()
        }

        stopButton.setOnClickListener {
            stopSearch(
                getString(R.string.ip_finder_stopped),
                R.color.barka_text_secondary
            )
        }

        scanButton.setOnClickListener {
            if (searching) {
                stopSearch(
                    getString(R.string.ip_finder_stopped),
                    R.color.barka_text_secondary
                )
            } else {
                startSearch()
            }
        }

        setAssistantButton.setOnClickListener {
            requestAssistantSelection()
        }

        ContextCompat.registerReceiver(
            this,
            cycleReceiver,
            IntentFilter(BarkaAssistantService.ACTION_CYCLE_COMPLETED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        refreshAssistantState()
        refreshNetworkState()
    }

    override fun onResume() {
        super.onResume()
        refreshAssistantState()
        refreshNetworkState()
    }

    override fun onDestroy() {
        searching = false
        handler.removeCallbacksAndMessages(null)
        BarkaAssistantService.cancelAirplaneCycle(this)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(cycleReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun requestAssistantSelection() {
        if (BarkaAssistantService.isSelected(this)) {
            openAssistantSettingsFallback()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                if (roleIntent.resolveActivity(packageManager) != null) {
                    runCatching { roleRequestLauncher.launch(roleIntent) }
                        .onFailure { openAssistantSettingsFallback() }
                    return
                }
            }
        }

        openAssistantSettingsFallback()
    }

    private fun openAssistantSettingsFallback() {
        val intents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val target = intents.firstOrNull { it.resolveActivity(packageManager) != null }
        if (target == null) {
            Toast.makeText(
                this,
                R.string.ip_finder_assistant_unavailable,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(
            this,
            R.string.ip_finder_assistant_settings_hint,
            Toast.LENGTH_LONG
        ).show()
        startActivity(target)
    }

    private fun refreshAssistantState() {
        if (!::setAssistantButton.isInitialized) return
        setAssistantButton.setText(
            if (BarkaAssistantService.isSelected(this)) {
                R.string.ip_finder_assistant_selected
            } else {
                R.string.ip_finder_set_assistant
            }
        )
    }

    private fun refreshNetworkState() {
        if (!::wifiWarning.isInitialized) return
        val wifiActive = isWifiActive()
        wifiWarning.setText(
            if (wifiActive) {
                R.string.ip_finder_wifi_warning
            } else {
                R.string.ip_finder_wifi_ready
            }
        )
        wifiWarning.setTextColor(
            ContextCompat.getColor(
                this,
                if (wifiActive) R.color.barka_red else R.color.barka_green
            )
        )

        if (!searching) {
            val currentIp = currentCellularIpv4()
            statusText.setText(R.string.ip_finder_idle)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.barka_blue))
            progressText.setText(R.string.ip_finder_current_cellular_ip)
            resultText.text = currentIp ?: getString(R.string.ip_finder_no_cellular_ip)
            resultText.setTextColor(ContextCompat.getColor(this, R.color.barka_text))
        }
    }

    private fun startSearch() {
        if (isWifiActive()) {
            Toast.makeText(
                this,
                R.string.ip_finder_turn_off_wifi,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!BarkaAssistantService.isSelected(this)) {
            Toast.makeText(
                this,
                R.string.ip_finder_select_assistant,
                Toast.LENGTH_LONG
            ).show()
            requestAssistantSelection()
            return
        }

        val rawPattern = ipInput.text.toString().trim()
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SEARCH_PATTERN, rawPattern)
            .apply()

        searchPatterns = parsePatterns(rawPattern)
        previousIp = currentCellularIpv4()
        attempts = 0
        searching = true
        scanButton.isEnabled = false
        stopButton.visibility = View.VISIBLE
        statusText.setText(R.string.ip_finder_searching)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.barka_orange))
        progressText.setText(R.string.ip_finder_waiting_for_network)
        resultText.text = previousIp ?: getString(R.string.ip_finder_no_cellular_ip)
        resultText.setTextColor(ContextCompat.getColor(this, R.color.barka_text))
        AppLogStore.add(this, getString(R.string.ip_finder_log_started))

        disconnectActiveTunnel()
        handler.postDelayed({ requestNextCycle() }, DISCONNECT_SETTLE_DELAY_MS)
    }

    private fun requestNextCycle() {
        if (!searching) return

        attempts += 1
        cycleRequestRetries = 0
        statusText.setText(R.string.ip_finder_searching)
        progressText.text = getString(
            R.string.ip_finder_attempt_format,
            attempts
        )
        requestAirplaneCycleWithRetry()
    }

    private fun requestAirplaneCycleWithRetry() {
        if (!searching) return
        if (BarkaAssistantService.requestAirplaneCycle(this)) return

        if (BarkaAssistantService.isSelected(this)) {
            cycleRequestRetries += 1
            handler.postDelayed(
                { requestAirplaneCycleWithRetry() },
                SERVICE_READY_RETRY_DELAY_MS
            )
            return
        }

        stopSearch(
            getString(R.string.ip_finder_cycle_failed),
            R.color.barka_red
        )
    }

    private fun waitForCellularIp(poll: Int) {
        if (!searching) return
        statusText.setText(R.string.ip_finder_waiting_for_network)
        val candidate = currentCellularIpv4()
        if (candidate != null && candidate != previousIp) {
            previousIp = candidate
            resultText.text = candidate
            if (matchesSearchPattern(candidate)) {
                completeSearch(candidate)
            } else {
                handler.postDelayed({ requestNextCycle() }, NEXT_CYCLE_DELAY_MS)
            }
            return
        }

        if (poll < MAX_NETWORK_POLLS) {
            handler.postDelayed(
                { waitForCellularIp(poll + 1) },
                NETWORK_POLL_DELAY_MS
            )
        } else {
            requestNextCycle()
        }
    }

    private fun completeSearch(ip: String) {
        searching = false
        handler.removeCallbacksAndMessages(null)
        BarkaAssistantService.cancelAirplaneCycle(this)
        scanButton.isEnabled = true
        stopButton.visibility = View.GONE
        statusText.setText(R.string.ip_finder_found)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.barka_green))
        progressText.setText(R.string.ip_finder_current_cellular_ip)
        resultText.text = getString(R.string.ip_finder_found_detail, ip)
        resultText.setTextColor(ContextCompat.getColor(this, R.color.barka_green))
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_FOUND_IP, ip)
            .apply()
        AppLogStore.add(
            this,
            getString(R.string.ip_finder_log_found_format, ip)
        )
    }

    private fun stopSearch(message: String, colorRes: Int) {
        searching = false
        handler.removeCallbacksAndMessages(null)
        BarkaAssistantService.cancelAirplaneCycle(this)
        scanButton.isEnabled = true
        stopButton.visibility = View.GONE
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, colorRes))
        AppLogStore.add(this, "IP Finder • $message")
    }

    private fun parsePatterns(value: String): List<String> = value
        .split(Regex("[\\n,;]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    private fun matchesSearchPattern(ip: String): Boolean {
        if (searchPatterns.isEmpty()) return true
        return searchPatterns.any { pattern ->
            when {
                pattern.startsWith("=") -> {
                    val expected = pattern.drop(1).trim()
                    expected.isNotBlank() && ip == expected
                }
                pattern.startsWith("^") && pattern.endsWith("$") -> {
                    val expected = pattern.drop(1).dropLast(1).trim()
                    expected.isNotBlank() && ip == expected
                }
                pattern.startsWith("^") -> {
                    val expected = pattern.drop(1).trim()
                    expected.isNotBlank() && ip.startsWith(expected)
                }
                pattern.endsWith("$") -> {
                    val expected = pattern.dropLast(1).trim()
                    expected.isNotBlank() && ip.endsWith(expected)
                }
                else -> ip.contains(pattern)
            }
        }
    }

    private fun currentCellularIpv4(): String? =
        connectivityManager.allNetworks.asSequence()
            .mapNotNull { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return@mapNotNull null
                }
                connectivityManager.getLinkProperties(network)
                    ?.linkAddresses
                    ?.asSequence()
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            }
            .firstOrNull()

    private fun isWifiActive(): Boolean =
        connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    private fun disconnectActiveTunnel() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BarkaVpnService::class.java).apply {
                    action = BarkaVpnService.ACTION_DISCONNECT
                }
            )
        }
    }

    companion object {
        const val PREFERENCES_NAME = "barka_ipfinder"
        private const val KEY_SEARCH_PATTERN = "search_pattern"
        const val KEY_LAST_FOUND_IP = "last_found_ip"
        private const val DEFAULT_SEARCH_PATTERN = "10.161;10.76;10.74;10.102;10.46;10.75;10.102;10.195;10.196;10.197;10.198;10.199;10.204;10.205;10.206;10.207;10.208;10.210,10.212,10.213;10.214;10.215;10.216;10.217;10.218;10.219;10.220;10.221;10.222;10.223;10.224;10.225;10.226;10.227;10.228;10.229;10.230;10.143;10.165"
        private const val MAX_NETWORK_POLLS = 15
        private const val DISCONNECT_SETTLE_DELAY_MS = 900L
        private const val SERVICE_READY_RETRY_DELAY_MS = 500L
        private const val NETWORK_POLL_DELAY_MS = 1_000L
        private const val NEXT_CYCLE_DELAY_MS = 900L
    }
}
