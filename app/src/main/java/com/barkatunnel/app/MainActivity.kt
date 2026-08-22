package com.barkatunnel.app

// BARKA_HOME_RUNTIME_V5_FINAL_NAV_NO_LOGIN

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.guide.GuideActivity
import com.barkatunnel.app.ipfinder.IpFinderActivity
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.journal.JournalActivity
import com.barkatunnel.app.networkinfo.NetworkIpProvider
import com.barkatunnel.app.settings.SettingsActivity
import com.barkatunnel.app.subscription.ActivationActivity
import com.barkatunnel.app.subscription.SubscriptionActivity
import com.barkatunnel.app.ui.home.HomeConnectionState
import com.barkatunnel.app.ui.home.HomeController
import com.barkatunnel.app.ui.home.HomeControllerResult
import com.barkatunnel.app.ui.home.HomeRuntimeFactory
import com.barkatunnel.app.ui.home.HomeTimerController
import com.barkatunnel.app.ui.home.HomeUiBinder
import com.barkatunnel.app.ui.home.NetworkOption
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.update.AppUpdateCoordinator
import com.barkatunnel.app.vpn.VpnPermissionHelper
import com.barkatunnel.app.vpnprofile.VpnProfileRepository
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var selectedNetwork: NetworkOption? = NetworkOption.ALL.firstOrNull()
    private var homeController: HomeController? = null

    private lateinit var networkIpValue: TextView
    private lateinit var networkIpStatus: TextView
    private lateinit var networkLogo: ImageView
    private lateinit var networkName: TextView
    private lateinit var networkSubtitle: TextView
    private lateinit var accessRemainingTime: TextView
    private lateinit var accessStatus: TextView
    private lateinit var connectionTime: TextView
    private lateinit var vpnStatus: TextView
    private lateinit var connectButton: MaterialButton

    private lateinit var uiBinder: HomeUiBinder
    private lateinit var timerController: HomeTimerController
    private lateinit var updateCoordinator: AppUpdateCoordinator
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingVpnPermissionAction: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingVpnPermissionAction
        pendingVpnPermissionAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            action?.invoke()
        } else {
            AppLogStore.add(this, "Autorisation VPN refusée par Android.")
            Toast.makeText(
                this,
                "Autorisation VPN nécessaire pour se connecter.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val vpnProfileRepository by lazy {
        VpnProfileRepository(BarkaBackendClient(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        updateCoordinator = AppUpdateCoordinator(this)

        val networkSelector = findViewById<android.view.View>(R.id.networkSelector)
        networkLogo = findViewById(R.id.networkLogo)
        networkName = findViewById(R.id.networkName)
        networkSubtitle = findViewById(R.id.networkSubtitle)

        val buttonFreeTrial = findViewById<android.view.View>(R.id.buttonFreeTrial)
        val buttonRefresh = findViewById<android.view.View>(R.id.buttonRefresh)
        connectButton = findViewById(R.id.connectButton)
        val powerButton = findViewById<TextView>(R.id.powerButton)

        networkIpValue = findViewById(R.id.networkIpValue)
        networkIpStatus = findViewById(R.id.networkIpStatus)

        val openIpFinder = {
            AppLogStore.add(this, "Ouverture de l’IP Finder.")
            startActivity(Intent(this, IpFinderActivity::class.java))
        }
        networkIpValue.setOnClickListener { openIpFinder() }
        networkIpStatus.setOnClickListener { openIpFinder() }
        findViewById<android.view.View>(R.id.buttonCopyIp).setOnClickListener {
            val ip = networkIpValue.text.toString().trim()
            if (ip.isNotBlank() && ip != "Indisponible") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("IP Barka Tunnel", ip))
                Toast.makeText(this, "IP copiée.", Toast.LENGTH_SHORT).show()
            }
        }

        accessRemainingTime = findViewById(R.id.accessRemainingTime)
        accessStatus = findViewById(R.id.accessStatus)
        connectionTime = findViewById(R.id.connectionTime)
        vpnStatus = findViewById(R.id.vpnStatus)

        uiBinder = HomeUiBinder(
            networkName = networkName,
            networkSubtitle = networkSubtitle,
            vpnStatus = vpnStatus,
            connectionTime = connectionTime,
            accessRemainingTime = accessRemainingTime,
            accessStatus = accessStatus,
            connectButton = connectButton
        )

        updateSelectedNetworkLogo(selectedNetwork)

        timerController = HomeTimerController(
            onAccessTick = { seconds ->
                accessRemainingTime.text = formatDuration(seconds)

                if (seconds <= 0L && accessStatus.text == "Accès actif") {
                    accessStatus.text = "Aucun temps actif"
                }
            },
            onConnectionTick = { seconds ->
                connectionTime.text =
                    "Temps de connexion : ${formatDuration(seconds)}"
            }
        )

        timerController.start()
        AppLogStore.add(this, "Barka Tunnel démarré.")
        refreshNetworkIp()
        initializeHomeRuntime()
        registerNetworkCallback()

        networkSelector.setOnClickListener {
            showNetworkDialog()
        }

        buttonFreeTrial.setOnClickListener {
            val controller = requireController() ?: return@setOnClickListener

            runHomeAction {
                val result = controller.startFreeTrial()

                if (result is HomeControllerResult.State) {
                    timerController.syncAccessRemaining(
                        result.value.access.remainingSeconds
                    )
                    val message = result.value.access.label
                    AppLogStore.add(this, "Essai 1H • $message")
                    runOnUiThread {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }

                result
            }
        }

        buttonRefresh.setOnClickListener {
            refreshNetworkIp()
            refreshVpnServices()
            updateCoordinator.check(showNoUpdate = true, force = true)

            val controller = requireController() ?: return@setOnClickListener

            runHomeAction(
                successMessage = "Accès et informations actualisés."
            ) {
                val accessResult = controller.refreshAccess()

                if (accessResult is HomeControllerResult.State) {
                    timerController.syncAccessRemaining(
                        accessResult.value.access.remainingSeconds
                    )
                }

                accessResult
            }
        }

        val connectAction = connectAction@{
            if (updateCoordinator.showBlockingIfNeeded()) {
                return@connectAction
            }

            val controller = requireController()

            if (controller != null) {
                val currentConnection =
                    controller.currentState().connection

                runHomeAction {
                    if (currentConnection is HomeConnectionState.Connected) {
                        val result = controller.disconnect()

                        if (
                            result is HomeControllerResult.State &&
                            result.value.connection is HomeConnectionState.Disconnected
                        ) {
                            timerController.stopConnectionTimer()
                            AppLogStore.add(this, "VPN déconnecté.")
                        }

                        result
                    } else {
                        val permissionIntent = VpnPermissionHelper.prepare(this)
                        if (permissionIntent != null) {
                            pendingVpnPermissionAction = { connectButton.performClick() }
                            runOnUiThread { vpnPermissionLauncher.launch(permissionIntent) }
                            return@runHomeAction HomeControllerResult.State(controller.currentState())
                        }

                        val selected = controller.currentState().selectedNetwork
                        AppLogStore.add(
                            this,
                            "Tentative de connexion${selected?.let { " • ${it.displayName}" } ?: ""}."
                        )

                        val result = controller.connect()

                        if (
                            result is HomeControllerResult.State &&
                            result.value.connection is HomeConnectionState.Connected
                        ) {
                            timerController.startConnectionTimer()
                            AppLogStore.add(
                                this,
                                "VPN connecté • ${(result.value.connection as HomeConnectionState.Connected).networkName}."
                            )
                        }

                        result
                    }
                }
            }
        }

        connectButton.setOnClickListener { connectAction() }
        powerButton.setOnClickListener { connectAction() }

        findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            refreshNetworkIp()
            refreshHomeState()
        }

        findViewById<android.view.View>(R.id.navIpFinder).setOnClickListener {
            startActivity(Intent(this, IpFinderActivity::class.java))
        }

        findViewById<android.view.View>(R.id.navJournal).setOnClickListener {
            startActivity(Intent(this, JournalActivity::class.java))
        }

        findViewById<android.view.View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<android.view.View>(R.id.buttonMenu).setOnClickListener {
            showSideMenu()
        }
    }

    override fun onResume() {
        super.onResume()

        if (::networkIpValue.isInitialized) {
            refreshNetworkIp()
            initializeHomeRuntime()
            if (::updateCoordinator.isInitialized) {
                updateCoordinator.check(showNoUpdate = false)
            }
        }
    }

    override fun onDestroy() {
        if (::timerController.isInitialized) {
            timerController.stop()
        }
        unregisterNetworkCallback()
        super.onDestroy()
    }

    private fun initializeHomeRuntime() {
        val app = application as BarkaApplication

        val runtime = HomeRuntimeFactory.create(
            context = this,
            container = app.container
        )

        homeController = HomeController(runtime)
        accessStatus.text = "En attente d’activation"

        val networkToRestore = selectedNetwork ?: NetworkOption.ALL.firstOrNull()
        if (networkToRestore != null && homeController?.currentState()?.selectedNetwork == null) {
            handleHomeResult(homeController!!.selectNetwork(networkToRestore))
        }

        refreshHomeState()
    }

    private fun refreshHomeState() {
        val controller = homeController ?: return

        Thread {
            val accessResult = controller.refreshAccess()

            runOnUiThread {
                handleHomeResult(accessResult)

                if (accessResult is HomeControllerResult.State) {
                    timerController.syncAccessRemaining(
                        accessResult.value.access.remainingSeconds
                    )
                }
            }
        }.start()
    }

    private fun showNetworkDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_network_selection)

        val selectedId =
            homeController?.currentState()?.selectedNetwork?.id
                ?: selectedNetwork?.id
                ?: NetworkOption.ALL.firstOrNull()?.id

        val checkMoov = dialog.findViewById<TextView>(R.id.optionMoovCheck)
        val checkOrange = dialog.findViewById<TextView>(R.id.optionOrangeCheck)
        val checkTelecel = dialog.findViewById<TextView>(R.id.optionTelecelCheck)

        checkMoov.text = if (selectedId == "moov_bf") "✓" else "○"
        checkOrange.text = if (selectedId == "orange_bf") "✓" else "○"
        checkTelecel.text = if (selectedId == "telecel_bf") "✓" else "○"

        fun select(networkId: String) {
            val network = NetworkOption.ALL.firstOrNull { it.id == networkId }
                ?: return

            selectedNetwork = network
            AppLogStore.add(
                this,
                "Réseau sélectionné • ${network.displayName}."
            )

            val controller = homeController
            if (controller != null) {
                handleHomeResult(
                    controller.selectNetwork(network)
                )
            } else {
                uiBinder.showNetwork(network)
                updateSelectedNetworkLogo(network)
            }

            refreshNetworkIp()
            dialog.dismiss()
        }

        dialog.findViewById<android.view.View>(R.id.networkDialogClose)
            .setOnClickListener { dialog.dismiss() }

        dialog.findViewById<android.view.View>(R.id.optionMoov)
            .setOnClickListener { select("moov_bf") }

        dialog.findViewById<android.view.View>(R.id.optionOrange)
            .setOnClickListener { select("orange_bf") }

        dialog.findViewById<android.view.View>(R.id.optionTelecel)
            .setOnClickListener { select("telecel_bf") }

        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun requireController(): HomeController? {
        val controller = homeController

        if (controller != null) {
            return controller
        }

        AppLogStore.add(this, "Contrôleur VPN indisponible.")
        Toast.makeText(
            this,
            "Initialisation du VPN en cours. Réessaie.",
            Toast.LENGTH_SHORT
        ).show()

        initializeHomeRuntime()
        return homeController
    }

    private fun runHomeAction(
        successMessage: String? = null,
        action: () -> HomeControllerResult
    ) {
        connectButton.isEnabled = false

        Thread {
            val result = action()

            runOnUiThread {
                connectButton.isEnabled = true
                handleHomeResult(result)

                if (
                    successMessage != null &&
                    result is HomeControllerResult.State
                ) {
                    Toast.makeText(
                        this,
                        successMessage,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun handleHomeResult(
        result: HomeControllerResult
    ) {
        when (result) {
            is HomeControllerResult.State -> {
                selectedNetwork =
                    result.value.selectedNetwork

                uiBinder.showNetwork(
                    result.value.selectedNetwork
                )
                updateSelectedNetworkLogo(
                    result.value.selectedNetwork
                )

                uiBinder.showAccess(
                    result.value.access
                )

                uiBinder.showConnection(
                    result.value.connection
                )
            }

            is HomeControllerResult.Message -> {
                AppLogStore.add(this, "Échec / information : ${result.text}")
                Toast.makeText(
                    this,
                    result.text,
                    Toast.LENGTH_LONG
                ).show()
            }

            HomeControllerResult.LoginRequired -> {
                AppLogStore.add(this, "Accès non actif : essai ou abonnement requis.")
                accessRemainingTime.text = "00:00:00"
                accessStatus.text = "Active l’essai 1H ou un abonnement"
                Toast.makeText(
                    this,
                    "Active l’essai 1H ou un abonnement pour continuer.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showSideMenu() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_side_menu)

        dialog.window?.apply {
            setBackgroundDrawable(
                ColorDrawable(Color.TRANSPARENT)
            )
            setGravity(Gravity.START)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.82).toInt(),
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }

        dialog.findViewById<android.view.View>(R.id.menuHome)
            .setOnClickListener {
                dialog.dismiss()
            }

        dialog.findViewById<android.view.View>(R.id.menuGuide)
            .setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, GuideActivity::class.java))
            }

        dialog.findViewById<android.view.View>(R.id.menuSubscription)
            .setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, SubscriptionActivity::class.java))
            }

        dialog.findViewById<android.view.View>(R.id.menuActivation)
            .setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, ActivationActivity::class.java))
            }

        dialog.findViewById<android.view.View>(R.id.menuJournal)
            .setOnClickListener {
                dialog.dismiss()
                startActivity(
                    Intent(this, JournalActivity::class.java)
                )
            }

        dialog.findViewById<android.view.View>(R.id.menuIpFinder)
            .setOnClickListener {
                dialog.dismiss()
                startActivity(
                    Intent(this, IpFinderActivity::class.java)
                )
            }

        dialog.findViewById<android.view.View>(R.id.menuSettings)
            .setOnClickListener {
                dialog.dismiss()
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                )
            }

        dialog.findViewById<android.view.View>(R.id.menuSupport)
            .setOnClickListener {
                dialog.dismiss()
                try {
                    val uri = Uri.parse("https://wa.me/message/XUBALKJE5J2CB1")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Impossible d’ouvrir le support pour le moment.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        dialog.show()

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.82).toInt(),
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun refreshVpnServices() {
        Thread {
            when (val result = vpnProfileRepository.refreshCatalog()) {
                is com.barkatunnel.app.vpnprofile.VpnProfileSyncResult.Success -> {
                    AppLogStore.add(
                        this,
                        "Services VPN actualisés • ${result.enabledCount} actif(s)."
                    )
                }

                is com.barkatunnel.app.vpnprofile.VpnProfileSyncResult.Error -> {
                    AppLogStore.add(
                        this,
                        "Actualisation des services impossible • ${result.message}"
                    )
                }
            }
        }.start()
    }

    private fun refreshNetworkIp() {
        val info = NetworkIpProvider.getCurrent(this)

        networkIpValue.text = info.ip
        networkIpStatus.text =
            "${info.transport} • IP actuelle"
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshFromNetworkCallback()
            override fun onLost(network: Network) = refreshFromNetworkCallback()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                refreshFromNetworkCallback()
        }
        networkCallback = callback
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        } finally {
            networkCallback = null
        }
    }

    private fun refreshFromNetworkCallback() {
        runOnUiThread {
            if (!isFinishing && ::networkIpValue.isInitialized) {
                refreshNetworkIp()
            }
        }
    }

    private fun updateSelectedNetworkLogo(
        network: NetworkOption?
    ) {
        val drawable = when (network?.id) {
            "orange_bf" -> R.drawable.ic_operator_orange
            "telecel_bf" -> R.drawable.ic_operator_telecel
            else -> R.drawable.ic_operator_moov
        }

        networkLogo.setImageResource(drawable)
    }

    private fun formatDuration(
        totalSeconds: Long
    ): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val seconds = safe % 60L

        return String.format(
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }
}
