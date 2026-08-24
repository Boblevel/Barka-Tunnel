package com.barkatunnel.app

// BARKA_HOME_RUNTIME_V5_FINAL_NAV_NO_LOGIN

import android.app.Dialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.barkatunnel.app.BuildConfig
import com.barkatunnel.app.guide.GuideActivity
import com.barkatunnel.app.ipfinder.IpFinderActivity
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.journal.JournalActivity
import com.barkatunnel.app.networkinfo.NetworkIpProvider
import com.barkatunnel.app.settings.SettingsActivity
import com.barkatunnel.app.subscription.ActivationActivity
import com.barkatunnel.app.subscription.SubscriptionActivity
import com.barkatunnel.app.support.SupportActivity
import com.barkatunnel.app.ui.home.HomeConnectionState
import com.barkatunnel.app.ui.home.HomeController
import com.barkatunnel.app.ui.home.HomeControllerResult
import com.barkatunnel.app.ui.home.HomeRuntimeFactory
import com.barkatunnel.app.ui.home.HomeTimerController
import com.barkatunnel.app.ui.home.HomeUiBinder
import com.barkatunnel.app.ui.home.NetworkOption
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.update.AppUpdateCoordinator
import com.barkatunnel.app.vpnprofile.VpnProfileRepository
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var selectedNetwork: NetworkOption? = NetworkOption.ALL.firstOrNull()
    private var homeController: HomeController? = null

    private lateinit var networkIpValue: TextView
    private lateinit var networkIpStatus: TextView
    private lateinit var networkLogo: ImageView
    private lateinit var networkTransportIcon: ImageView
    private lateinit var networkName: TextView
    private lateinit var networkSubtitle: TextView
    private lateinit var accessRemainingTime: TextView
    private lateinit var accessStatus: TextView
    private lateinit var connectionTime: TextView
    private lateinit var vpnStatus: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var powerButton: TextView

    private lateinit var uiBinder: HomeUiBinder
    private lateinit var timerController: HomeTimerController
    private lateinit var updateCoordinator: AppUpdateCoordinator
    private lateinit var connectAction: () -> Unit
    private var networkCallbackRegistered = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            AppLogStore.add(this, "Permission VPN Android accordée.")
            if (::connectAction.isInitialized) connectAction()
        } else {
            AppLogStore.add(this, "Permission VPN Android refusée.")
            Toast.makeText(this, "Permission VPN requise pour se connecter.", Toast.LENGTH_SHORT).show()
        }
    }

    private val connectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetworkIpAsync()
        override fun onLost(network: Network) = refreshNetworkIpAsync()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshNetworkIpAsync()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshNetworkIpAsync()
    }

    private val vpnProfileRepository by lazy {
        VpnProfileRepository(BarkaBackendClient(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBars()
        updateCoordinator = AppUpdateCoordinator(this)

        val networkSelector = findViewById<android.view.View>(R.id.networkSelector)
        networkLogo = findViewById(R.id.networkLogo)
        networkName = findViewById(R.id.networkName)
        networkSubtitle = findViewById(R.id.networkSubtitle)

        val buttonFreeTrial = findViewById<android.view.View>(R.id.buttonFreeTrial)
        val buttonRefresh = findViewById<android.view.View>(R.id.buttonRefresh)
        val addAccessButton = findViewById<android.view.View>(R.id.addAccessButton)
        connectButton = findViewById(R.id.connectButton)
        powerButton = findViewById(R.id.powerButton)

        networkIpValue = findViewById(R.id.networkIpValue)
        networkIpStatus = findViewById(R.id.networkIpStatus)
        networkTransportIcon = findViewById(R.id.networkTransportIcon)

        val openIpFinder = {
            AppLogStore.add(this, "Ouverture de l’IP Finder.")
            startActivity(Intent(this, IpFinderActivity::class.java))
        }
        networkIpValue.setOnClickListener { openIpFinder() }
        networkIpStatus.setOnClickListener { openIpFinder() }

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

        addAccessButton.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        connectAction = connectAction@{
            if (updateCoordinator.showBlockingIfNeeded()) {
                return@connectAction
            }

            val controller = requireController()

            if (controller != null) {
                val currentConnection =
                    controller.currentState().connection

                if (currentConnection !is HomeConnectionState.Connected) {
                    val permissionIntent = VpnService.prepare(this)
                    if (permissionIntent != null) {
                        AppLogStore.add(this, "Demande de permission VPN Android.")
                        vpnPermissionLauncher.launch(permissionIntent)
                        return@connectAction
                    }
                }

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
                        val selected = controller.currentState().selectedNetwork
                        AppLogStore.add(
                            this,
                            "Tentative de connexion${selected?.let { " • ${it.displayName}" } ?: ""}."
                        )
                        runOnUiThread {
                            uiBinder.showConnection(HomeConnectionState.Connecting)
                            updatePowerButtonState(HomeConnectionState.Connecting)
                        }

                        val result = controller.connect()

                        if (
                            result is HomeControllerResult.State &&
                            result.value.connection is HomeConnectionState.Connected
                        ) {
                            timerController.startConnectionTimer()
                            vibrateOnce(CONNECTED_VIBRATION_MS)
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

        connectButton.setOnClickListener {
            vibrateOnce(PRESS_VIBRATION_MS)
            connectAction()
        }
        powerButton.setOnClickListener {
            vibrateOnce(PRESS_VIBRATION_MS)
            connectAction()
        }

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

        if (::networkIpValue.isInitialized) {
            applySystemBars()
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

        val initialNetwork = selectedNetwork
            ?: NetworkOption.ALL.firstOrNull()
        if (initialNetwork != null && homeController?.currentState()?.selectedNetwork == null) {
            handleHomeResult(homeController!!.selectNetwork(initialNetwork))
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
                updatePowerButtonState(result.value.connection)
            }

            is HomeControllerResult.Message -> {
                homeController?.currentState()?.connection?.let { connection ->
                    uiBinder.showConnection(connection)
                    updatePowerButtonState(connection)
                }
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

    private fun updatePowerButtonState(state: HomeConnectionState) {
        if (!::powerButton.isInitialized) return
        powerButton.setBackgroundResource(
            if (state is HomeConnectionState.Connected) {
                R.drawable.bg_power_connected
            } else {
                R.drawable.bg_power_final
            }
        )
    }

    private fun vibrateOnce(durationMs: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
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
            setGravity(Gravity.END)
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

        dialog.findViewById<TextView>(R.id.menuVersion).text = "Version ${BuildConfig.VERSION_NAME}"

        dialog.findViewById<android.view.View>(R.id.menuSupport)
            .setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, SupportActivity::class.java))
            }

        dialog.show()

        dialog.window?.apply {
            navigationBarColor = ContextCompat.getColor(this@MainActivity, R.color.barka_card)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.82).toInt(),
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
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
        networkIpStatus.text = "${info.transport} • IP actuelle"

        if (::networkTransportIcon.isInitialized) {
            val icon = when (info.transport) {
                "Données mobiles" -> R.drawable.ic_mobile_barka
                else -> R.drawable.ic_wifi_barka
            }
            networkTransportIcon.setImageResource(icon)
        }
    }

    private fun refreshNetworkIpAsync() {
        if (!::networkIpValue.isInitialized) return
        runOnUiThread { refreshNetworkIp() }
    }

    private fun updateSelectedNetworkLogo(
        network: NetworkOption?
    ) {
        val drawable = when (network?.id) {
            "orange_bf" -> R.drawable.ic_operator_orange
            "telecel_bf" -> R.drawable.ic_operator_telecel
            else -> R.drawable.moov_africa_official
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

    companion object {
        private const val PRESS_VIBRATION_MS = 45L
        private const val CONNECTED_VIBRATION_MS = 90L
    }
}
