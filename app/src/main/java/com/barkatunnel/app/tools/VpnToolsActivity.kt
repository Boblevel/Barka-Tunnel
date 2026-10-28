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
import android.content.Intent
import androidx.core.content.ContextCompat

class VpnToolsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var value: TextView
    private lateinit var action: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var addresses: Spinner
    private val handler = Handler(Looper.getMainLooper())
    private var localAddresses = emptyList<InetAddress>()
    private var speed: VpnSpeedTest? = null
    private var generation = 0
    private var isShare = false
    private var epoch = 0L
    private var port: Int? = null
    private val monitor = object : Runnable {
        override fun run() {
            if (isShare) renderSharing()
            if (speed != null && !vpnAlive()) stop(getString(R.string.tools_vpn_required))
            if (isShare || speed != null) handler.postDelayed(this, 1000)
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
        findViewById<TextView>(R.id.toolHelp).apply {
            visibility = if (isShare) View.GONE else View.VISIBLE
            if (!isShare) setText(R.string.vpn_speed_help)
        }
        addresses.visibility = if (isShare) View.VISIBLE else View.GONE
        findViewById<View>(R.id.shareAddressLabel).visibility = if (isShare) View.VISIBLE else View.GONE
        value.visibility = if (isShare) View.GONE else View.VISIBLE
        if (isShare) refreshAddresses()
        action.setOnClickListener {
            if ((isShare && VpnShareService.state != null) || speed != null) stop(getString(R.string.tools_stopped)) else start()
        }
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
            try {
                ContextCompat.startForegroundService(this, Intent(this, VpnShareService::class.java)
                    .putExtra("address", selected.hostAddress))
            } catch (_: Exception) { status.setText(R.string.tools_share_failed); return }
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
                } catch (error: Exception) {
                    val detail = when {
                        !vpnAlive() -> getString(R.string.tools_vpn_required)
                        error is java.net.SocketTimeoutException -> getString(R.string.speed_timeout)
                        error is javax.net.ssl.SSLException -> getString(R.string.speed_tls_error)
                        error.message?.startsWith("HTTP_") == true -> getString(R.string.speed_http_error, error.message!!.removePrefix("HTTP_"))
                        else -> getString(R.string.speed_network_error)
                    }
                    com.barkatunnel.app.journal.AppLogStore.add(this, "Test débit • ${error.javaClass.simpleName} • $detail")
                    runOnUiThread { if (token == generation && !isDestroyed) stop(detail) }
                } finally { test.close() }
            }.start()
        }
        handler.removeCallbacks(monitor); handler.post(monitor)
        action.setText(R.string.tools_stop)
        if (!isShare) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun stop(message: String) {
        generation++; speed?.close(); speed = null
        if (isShare) stopService(Intent(this, VpnShareService::class.java))
        progress.visibility = View.GONE; addresses.isEnabled = true
        status.text = message; action.setText(R.string.tools_start)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun renderSharing() {
        val current = VpnShareService.state
        addresses.isEnabled = current == null
        action.setText(if (current == null) R.string.tools_start else R.string.tools_stop)
        findViewById<View>(R.id.shareDetails).visibility = if (current == null) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.shareAddress).text = current?.address.orEmpty()
        findViewById<TextView>(R.id.sharePort).text = current?.port?.toString().orEmpty()
        findViewById<TextView>(R.id.shareUsername).text = if (current == null) "" else "barka"
        findViewById<TextView>(R.id.sharePassword).text = current?.password.orEmpty()
        status.setText(if (current != null) R.string.share_active else VpnShareService.error ?: R.string.tools_ready)
    }
    override fun onResume() {
        super.onResume()
        if (isShare) { refreshAddresses(); handler.removeCallbacks(monitor); handler.post(monitor) }
    }
    override fun onPause() {
        if (isShare) handler.removeCallbacks(monitor)
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); generation++; speed?.close(); speed = null
        super.onDestroy()
    }
}
