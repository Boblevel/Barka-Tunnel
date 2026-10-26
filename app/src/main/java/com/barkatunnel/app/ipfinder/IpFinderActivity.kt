package com.barkatunnel.app.ipfinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.LinkProperties
import androidx.core.widget.doAfterTextChanged
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.ipfinder.assistant.BarkaAssistantService
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.networkinfo.NetworkIpProvider
import com.barkatunnel.app.ui.SystemBars
import com.barkatunnel.app.vpnc6.BarkaVpnService
import com.google.android.material.button.MaterialButton
import java.net.Inet4Address

class IpFinderActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Volatile private var searching = false
    private var networkRegistered = false
    private var lastCurrentIp: String? = null
    private var displayInitialized = false
    private val displayHandler = Handler(Looper.getMainLooper())
    private val displayPoll = object : Runnable {
        override fun run() {
            if (isDestroyed || isFinishing) return
            refreshNetworkState()
            displayHandler.postDelayed(this, NETWORK_POLL_DELAY_MS)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private fun refresh() { handler.post { if (!isDestroyed) refreshNetworkState() } }
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }
    private var receiverRegistered = false
    private var attempts = 0
    private var cycleRequestRetries = 0
    private var failedCycles = 0
    private var previousIp: String? = null
    private var searchPatterns: List<String> = emptyList()

    private lateinit var scanButton: MaterialButton
    private lateinit var manualAirplaneButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var setAssistantButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var resultText: TextView
    private lateinit var wifiWarning: TextView
    private lateinit var ipInput: EditText

    private val cycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { renderSearchState() }
    }

    private fun renderSearchState() {
        searching = IpSearchService.active
        stopButton.visibility = if (searching) View.VISIBLE else View.INVISIBLE
        ipInput.isEnabled = !searching
        refreshNetworkState()
        if (IpSearchService.status.isNotEmpty()) statusText.text = IpSearchService.status
        statusText.setTextColor(ContextCompat.getColor(this, if (searching) R.color.barka_orange
            else if (IpSearchService.foundIp != null) R.color.barka_green else R.color.barka_text_secondary))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ip_finder)
        SystemBars.apply(this)

        findViewById<View>(R.id.ipNetworkSettings).setOnClickListener {
            val preferred = if (isWifiActive()) Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_NETWORK_OPERATOR_SETTINGS
            val opened = listOf(preferred, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
                .any { action ->
                    val target = Intent(action)
                    if (action == Settings.ACTION_NETWORK_OPERATOR_SETTINGS && Build.VERSION.SDK_INT >= 24) {
                        val sim = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                        if (android.telephony.SubscriptionManager.isValidSubscriptionId(sim)) target.putExtra(Settings.EXTRA_SUB_ID, sim)
                    }
                    startIfResolvable(target)
                }
            if (!opened) Toast.makeText(this, R.string.ip_network_settings_unavailable, Toast.LENGTH_LONG).show()
        }
        ipInput = findViewById(R.id.ipInput)
        scanButton = findViewById(R.id.scanButton)
        manualAirplaneButton = findViewById(R.id.manualAirplaneButton)
        stopButton = findViewById(R.id.stopButton)
        setAssistantButton = findViewById(R.id.setAssistantButton)
        statusText = findViewById(R.id.scanStatus)
        progressText = findViewById(R.id.scanProgress)
        resultText = findViewById(R.id.scanResult)
        wifiWarning = findViewById(R.id.wifiWarning)

        ipInput.setText(loadSearchPattern(this))
        if (usesOrangeRules(this)) {
            ipInput.keyListener = null
            ipInput.setTextIsSelectable(true)
            findViewById<TextView>(R.id.ipPatternHelp).setText(R.string.ip_finder_orange_patterns)
        }
        ipInput.doAfterTextChanged { if (!searching) refreshNetworkState() }

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
        manualAirplaneButton.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }

        setAssistantButton.setOnClickListener {
            requestAssistantSelection()
        }

        ContextCompat.registerReceiver(
            this,
            cycleReceiver,
            IntentFilter(IpSearchService.CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        refreshAssistantState()
        refreshNetworkState()
    }

    override fun onResume() {
        super.onResume()
        if (::setAssistantButton.isInitialized) {
            setAssistantButton.isEnabled = true
        }
        refreshAssistantState()
        if (!networkRegistered) {
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            networkRegistered = true
        }
        renderSearchState()
        displayHandler.removeCallbacks(displayPoll)
        displayHandler.postDelayed(displayPoll, NETWORK_POLL_DELAY_MS)
    }

    override fun onPause() {
        if (networkRegistered) runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        networkRegistered = false
        displayHandler.removeCallbacks(displayPoll)
        super.onPause()
    }

    override fun onDestroy() {
        if (networkRegistered) connectivityManager.unregisterNetworkCallback(networkCallback)
        networkRegistered = false
        searching = false
        displayHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(cycleReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun requestAssistantSelection() {
        // La page montrée par Android pour choisir l'assistant numérique.
        // Le composant AOSP direct est prioritaire sur les ROM Transsion qui
        // résolvent parfois l'action publique vers une page vocale générique.
        val aospAssistantSettings = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$ManageAssistActivity"
            )
        }
        if (startIfResolvable(aospAssistantSettings)) {
            return
        }

        // Les autres constructeurs peuvent remplacer la page AOSP tout en
        // conservant l'action Android publique correspondante.
        val packagedAssistantSettings = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            setPackage("com.android.settings")
        }
        if (startIfResolvable(packagedAssistantSettings)) {
            return
        }

        if (startIfResolvable(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) {
            return
        }

        if (!openAssistantSettingsFallback()) {
            setAssistantButton.isEnabled = true
        }
    }

    private fun startIfResolvable(intent: Intent): Boolean {
        return runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun openAssistantSettingsFallback(): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val opened = intents.any(::startIfResolvable)
        if (!opened) {
            Toast.makeText(
                this,
                R.string.ip_finder_assistant_unavailable,
                Toast.LENGTH_LONG
            ).show()
        }
        return opened
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

        val currentIp = currentVisibleIpv4()
        val networkChanged = !displayInitialized || currentIp != lastCurrentIp
        displayInitialized = true
        lastCurrentIp = currentIp
        if (!searching) searchPatterns = parsePatterns(ipInput.text.toString())
        val compatible = currentIp != null && matchesSearchPattern(currentIp)
        resultText.text = currentIp ?: "—"
        resultText.setTextColor(ContextCompat.getColor(this, when {
            currentIp == null -> R.color.barka_text_secondary
            compatible -> R.color.barka_green
            else -> R.color.barka_red
        }))
        scanButton.visibility = if (compatible) View.GONE else View.VISIBLE
        if (compatible) manualAirplaneButton.visibility = View.GONE
        scanButton.isEnabled = !searching && currentIp != null

        if (!searching && networkChanged) {
            statusText.setText(R.string.ip_finder_idle)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.barka_blue))
            progressText.setText(R.string.ip_finder_current_ip)

        }
    }

    private fun startSearch() {
        searchPatterns = parsePatterns(ipInput.text.toString())
        val current = currentVisibleIpv4()
        if (current != null && matchesSearchPattern(current)) { refreshNetworkState(); return }
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
        if (!usesOrangeRules(this)) {
            getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SEARCH_PATTERN, rawPattern)
                .apply()
        }

        searchPatterns = parsePatterns(rawPattern)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 740)
            Toast.makeText(this, R.string.ip_search_notification_permission, Toast.LENGTH_LONG).show()
            return
        }
        disconnectActiveTunnel()
        runCatching { ContextCompat.startForegroundService(this, Intent(this, IpSearchService::class.java)) }
            .onFailure { Toast.makeText(this, R.string.ip_finder_cycle_failed, Toast.LENGTH_LONG).show() }
    }

    private fun stopSearch(message: String, colorRes: Int) {
        stopService(Intent(this, IpSearchService::class.java))
        searching = false
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, colorRes))
        stopButton.visibility = View.INVISIBLE
        ipInput.isEnabled = true
        refreshNetworkState()
    }

    private fun vibrateOnce() {
        if (isFinishing || isDestroyed) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            IP_FINDER_VIBRATION_MS,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(IP_FINDER_VIBRATION_MS)
                }
            } else if (::scanButton.isInitialized) {
                scanButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }

    private fun parsePatterns(value: String): List<String> = value
        .split(Regex("[\\n,;]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    private fun matchesSearchPattern(ip: String): Boolean {
        if (usesOrangeRules(this)) return OrangeIpPolicy.matches(ip)
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

    private fun currentVisibleIpv4(): String? {
        val wifi = if (isWifiActive()) connectivityManager.allNetworks.asSequence()
            .filter { connectivityManager.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .mapNotNull { network -> connectivityManager.getLinkProperties(network)?.linkAddresses
                ?.asSequence()?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress }
            .firstOrNull() else null
        return wifi ?: NetworkIpProvider.getCellularIpv4(this, includeVpn = false)
    }

    private fun isWifiActive(): Boolean =
        connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

    private fun disconnectActiveTunnel() {
        if (
            BarkaVpnService.connectionSnapshot().state ==
            BarkaVpnService.RuntimeConnectionState.DISCONNECTED
        ) {
            return
        }
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
        fun captureCriteria(context: Context) = IpSearchCriteria(usesOrangeRules(context), loadSearchPattern(context))
        fun isIpCompatible(context: Context, ip: String?): Boolean {
            if (ip.isNullOrBlank()) return false
            if (usesOrangeRules(context)) return OrangeIpPolicy.matches(ip)

            val rawPattern = loadSearchPattern(context)

            val patterns = rawPattern
                .split(Regex("[\\n,;]+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            if (patterns.isEmpty()) return true
            return patterns.any { pattern ->
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

        const val PREFERENCES_NAME = "barka_ipfinder"
        private const val KEY_SEARCH_PATTERN = "search_pattern"
        const val KEY_LAST_FOUND_IP = "last_found_ip"
        private const val DEFAULT_SEARCH_PATTERN = OrangeIpPolicy.PATTERNS
        private const val LEGACY_IP_SEQUENCE = "10.208;10.210"
        private const val UPDATED_IP_SEQUENCE = "10.208;10.209;10.210"
        private val RETIRED_IP_PREFIXES = setOf("10.148", "10.46", "10.193", "10.194", "10.80", "10.10194")
        private const val IP_FINDER_VIBRATION_MS = 70L
        private const val MAX_NETWORK_POLLS = 15
        private const val MAX_SERVICE_READY_RETRIES = 20
        private const val MAX_FAILED_CYCLES = 2
        private const val DISCONNECT_SETTLE_DELAY_MS = 900L
        private const val SERVICE_READY_RETRY_DELAY_MS = 500L
        private const val NETWORK_POLL_DELAY_MS = 10_000L
        private const val NEXT_CYCLE_DELAY_MS = 900L

        private fun usesOrangeRules(context: Context): Boolean =
            context.getSharedPreferences("barka_home_preferences", Context.MODE_PRIVATE)
                .getString("selected_network_id", null) == "orange_bf"

        private fun loadSearchPattern(context: Context): String {
            if (usesOrangeRules(context)) return OrangeIpPolicy.PATTERNS
            val preferences = context.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )
            val savedPattern = preferences.getString(KEY_SEARCH_PATTERN, "")
                .orEmpty()
            val resolvedPattern = savedPattern.ifBlank { DEFAULT_SEARCH_PATTERN }
            val patterns = resolvedPattern
                .replace(LEGACY_IP_SEQUENCE, UPDATED_IP_SEQUENCE)
                .split(Regex("[;,\\n]+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            var migratedPattern = patterns.filterNot { pattern ->
                    pattern.removePrefix("=")
                        .removePrefix("^")
                        .removeSuffix("$")
                        .trim() in RETIRED_IP_PREFIXES
                }
                .joinToString(";")
                .ifBlank { if (patterns.isNotEmpty()) DEFAULT_SEARCH_PATTERN.replace(',', ';') else "" }

            if (migratedPattern != savedPattern) {
                preferences.edit()
                    .putString(KEY_SEARCH_PATTERN, migratedPattern)
                    .apply()
            }
            return migratedPattern
        }
    }
}
