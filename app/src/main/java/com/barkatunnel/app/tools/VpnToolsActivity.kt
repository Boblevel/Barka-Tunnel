package com.barkatunnel.app.tools

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.SystemBars
import com.barkatunnel.app.vpnc6.BarkaVpnService
import com.google.android.material.button.MaterialButton
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

class VpnToolsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var value: TextView
    private lateinit var action: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var addresses: Spinner
    private val handler = Handler(Looper.getMainLooper())
    private var localAddresses = emptyList<InetAddress>()
    private var sharing: VpnShareProxy? = null
    private var speed: VpnSpeedTest? = null
    private var generation = 0
    private var isShare = false
    private var epoch = 0L
    private var port: Int? = null
    private val monitor = object : Runnable {
        override fun run() {
            if ((sharing != null || speed != null) && !vpnAlive()) stop(getString(R.string.tools_vpn_required))
            handler.postDelayed(this, 500)
        }
    }
    private fun vpnAlive() = port != null && BarkaVpnService.connectedSocksPort() == port &&
        BarkaVpnService.connectionSnapshot().connectedAtElapsedMs == epoch
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn_tools); SystemBars.apply(this)
        isShare = intent.getBooleanExtra("share", false)
        status = findViewById(R.id.toolStatus); value = findViewById(R.id.toolValue)
        action = findViewById(R.id.toolAction); progress = findViewById(R.id.toolProgress)
        addresses = findViewById(R.id.toolAddresses)
        findViewById<View>(R.id.toolBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.toolTitle).setText(if (isShare) R.string.vpn_share_title else R.string.vpn_speed_title)
        findViewById<TextView>(R.id.toolHelp).setText(if (isShare) R.string.vpn_share_help else R.string.vpn_speed_help)
        addresses.visibility = if (isShare) View.VISIBLE else View.GONE
        if (isShare) refreshAddresses()
        action.setOnClickListener {
            if (sharing != null || speed != null) stop(getString(R.string.tools_stopped)) else start()
        }
        handler.post(monitor)
    }
    private fun refreshAddresses() {
        localAddresses = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter {
                it.isUp && !it.isLoopback && !it.name.startsWith("tun") &&
                    !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni")
            }.flatMap { it.inetAddresses.toList() }.filter {
                it is Inet4Address && it.isSiteLocalAddress && it.hostAddress != "10.10.0.1"
            }.distinctBy { it.hostAddress }
        }.getOrDefault(emptyList())
        addresses.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            localAddresses.map { it.hostAddress }.ifEmpty { listOf(getString(R.string.tools_no_lan)) })
    }
    private fun start() {
        port = BarkaVpnService.connectedSocksPort()
        epoch = BarkaVpnService.connectionSnapshot().connectedAtElapsedMs
        if (!vpnAlive()) { status.setText(R.string.tools_vpn_required); return }
        if (isShare) {
            val selected = localAddresses.getOrNull(addresses.selectedItemPosition)
            if (selected == null) { refreshAddresses(); status.setText(R.string.tools_no_lan); return }
            val password = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val auth = android.util.Base64.encodeToString("barka:$password".toByteArray(), android.util.Base64.NO_WRAP)
            val proxy = VpnShareProxy(selected, port!!, "Basic $auth", ::vpnAlive)
            try {
                proxy.start(); sharing = proxy
                value.text = "${selected.hostAddress}:${proxy.port}"
                status.text = getString(R.string.tools_share_credentials, "barka", password)
                addresses.isEnabled = false
            } catch (_: Exception) { proxy.close(); status.setText(R.string.tools_share_failed); return }
        } else {
            val test = VpnSpeedTest(port!!, ::vpnAlive)
            speed = test; val token = ++generation
            value.text = "0.00 Mbps"; progress.visibility = View.VISIBLE
            Thread {
                try {
                    test.run { stage, mbps, details -> runOnUiThread {
                        if (token == generation && !isDestroyed) {
                            status.text = getString(stage) + "\n" + details
                            value.text = String.format(java.util.Locale.getDefault(), "%.2f Mbps", mbps)
                        }
                    } }
                    runOnUiThread { if (token == generation && !isDestroyed) {
                        speed = null; progress.visibility = View.GONE; action.setText(R.string.tools_start)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } }
                } catch (_: Exception) {
                    runOnUiThread { if (token == generation && !isDestroyed) stop(getString(R.string.tools_speed_failed)) }
                } finally { test.close() }
            }.start()
        }
        action.setText(R.string.tools_stop)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun stop(message: String) {
        generation++; speed?.close(); speed = null; sharing?.close(); sharing = null
        progress.visibility = View.GONE; addresses.isEnabled = true
        status.text = message; action.setText(R.string.tools_start)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); stop(getString(R.string.tools_stopped)); super.onDestroy()
    }
}
