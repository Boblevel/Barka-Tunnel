package com.barkatunnel.app

// BARKA_HOME_RUNTIME_V5_FINAL_NAV_NO_LOGIN

import android.Manifest
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
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
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import com.barkatunnel.app.BuildConfig
import com.barkatunnel.app.guide.GuideActivity
import com.barkatunnel.app.ipfinder.IpFinderActivity
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.journal.JournalUiBinder
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
import com.barkatunnel.app.ui.pager.StaticPageAdapter
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.update.AppUpdateCoordinator
import com.barkatunnel.app.vpnc6.BarkaVpnService
import com.barkatunnel.app.vpnprofile.VpnProfileRepository
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var selectedNetwork: NetworkOption? = null
    private var homeController: HomeController? = null
    private lateinit var homeJournalPager: ViewPager2
    private lateinit var journalUiBinder: JournalUiBinder
    private var journalLogListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLogStore.add(this, "Permission de notification accordée.")
            if (::connectAction.isInitialized) connectAction()
        } else {
            AppLogStore.add(this, "Permission de notification refusée.")
            Toast.makeText(
                this,
                "Autorise les notifications de Barka Tunnel pour afficher l’état de connexion.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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
        setContentView(R.layout.activity_home_journal_pager)
        val homePage = layoutInflater.inflate(R.layout.activity_main, null, false)
        val journalPage = layoutInflater.inflate(R.layout.activity_journal, null, false)
        homePage.findViewById<android.view.View>(R.id.pageBottomDivider).visibility =
            android.view.View.GONE
        homePage.findViewById<android.view.View>(R.id.pageBottomNavigation).visibility =
            android.view.View.GONE
        journalPage.findViewById<android.view.View>(R.id.pageBottomDivider).visibility =
            android.view.View.GONE
        journalPage.findViewById<android.view.View>(R.id.pageBottomNavigation).visibility =
            android.view.View.GONE
        homeJournalPager = findViewById(R.id.homeJournalPager)
        homeJournalPager.adapter = StaticPageAdapter(listOf(homePage, journalPage))
        homeJournalPager.offscreenPageLimit = 1
        homeJournalPager.setCurrentItem(PAGE_HOME, false)
        applySystemBars()
        updateCoordinator = AppUpdateCoordinator(this)
        selectedNetwork = loadSelectedNetwork()

        val networkSelector = homePage.findViewById<android.view.View>(R.id.networkSelector)
        networkLogo = homePage.findViewById(R.id.networkLogo)
        networkName = homePage.findViewById(R.id.networkName)
        networkSubtitle = homePage.findViewById(R.id.networkSubtitle)

        val buttonFreeTrial = homePage.findViewById<android.view.View>(R.id.buttonFreeTrial)
        val buttonRefresh = homePage.findViewById<android.view.View>(R.id.buttonRefresh)
        val addAccessButton = homePage.findViewById<android.view.View>(R.id.addAccessButton)
        connectButton = homePage.findViewById(R.id.connectButton)
        powerButton = homePage.findViewById(R.id.powerButton)

        networkIpValue = homePage.findViewById(R.id.networkIpValue)
        networkIpStatus = homePage.findViewById(R.id.networkIpStatus)
        networkTransportIcon = homePage.findViewById(R.id.networkTransportIcon)

        networkIpValue.setOnClickListener { copyNetworkIp() }
        networkIpStatus.setOnClickListener { copyNetworkIp() }
        homePage.findViewById<android.view.View>(R.id.networkIpCard)
            .setOnClickListener { copyNetworkIp() }

        accessRemainingTime = homePage.findViewById(R.id.accessRemainingTime)
        accessStatus = homePage.findViewById(R.id.accessStatus)
        connectionTime = homePage.findViewById(R.id.connectionTime)
        vpnStatus = homePage.findViewById(R.id.vpnStatus)

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
                timerController.syncAccessRemaining(
                    controller.currentState().access.remainingSeconds
                )

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
                val shouldDisconnect =
                    currentConnection is HomeConnectionState.Connected ||
                        currentConnection is HomeConnectionState.Connecting ||
                        currentConnection is HomeConnectionState.Error

                if (!shouldDisconnect) {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        AppLogStore.add(this, "Demande de permission de notification Android.")
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return@connectAction
                    }

                    val permissionIntent = VpnService.prepare(this)
                    if (permissionIntent != null) {
                        AppLogStore.add(this, "Demande de permission VPN Android.")
                        vpnPermissionLauncher.launch(permissionIntent)
                        return@connectAction
                    }

                    BarkaVpnService.showConnectingNotification(this)
                }

                runHomeAction {
                    if (shouldDisconnect) {
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
                        val connectedState =
                            (result as? HomeControllerResult.State)?.value?.connection
                        val finalConnection = controller.currentState().connection

                        if (connectedState is HomeConnectionState.Connected) {
                            timerController.startConnectionTimer()
                            vibrateOnce(CONNECTED_VIBRATION_MS)
                            AppLogStore.add(
                                this,
                                "VPN connecté • ${connectedState.networkName}."
                            )
                        } else if (
                            finalConnection is HomeConnectionState.Connecting ||
                            finalConnection is HomeConnectionState.Error
                        ) {
                            AppLogStore.add(
                                this,
                                "Connexion refusée${selected?.let { " • ${it.displayName}" } ?: ""}."
                            )
                            BarkaVpnService.showWaitingNotification(this)
                        } else {
                            BarkaVpnService.cancelConnectingNotification(this)
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

        val sharedNavHomeIcon = findViewById<ImageView>(R.id.sharedNavHomeIcon)
        val sharedNavHomeLabel = findViewById<TextView>(R.id.sharedNavHomeLabel)
        val sharedNavJournalIcon = findViewById<ImageView>(R.id.sharedNavJournalIcon)
        val sharedNavJournalLabel = findViewById<TextView>(R.id.sharedNavJournalLabel)
        var activeBottomPage = -1

        fun updateBottomNavigation(page: Int) {
            if (activeBottomPage == page) return
            activeBottomPage = page
            val homeActive = page == PAGE_HOME
            sharedNavHomeIcon.setImageResource(
                if (homeActive) R.drawable.ic_nav_home_active else R.drawable.ic_nav_home
            )
            sharedNavJournalIcon.setImageResource(
                if (homeActive) R.drawable.ic_nav_journal else R.drawable.ic_nav_journal_active
            )
            sharedNavHomeLabel.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (homeActive) R.color.barka_blue else R.color.barka_text_secondary
                )
            )
            sharedNavJournalLabel.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (homeActive) R.color.barka_text_secondary else R.color.barka_blue
                )
            )
            sharedNavHomeLabel.setTypeface(
                null,
                if (homeActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            sharedNavJournalLabel.setTypeface(
                null,
                if (homeActive) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD
            )
        }

        findViewById<android.view.View>(R.id.sharedNavHome).setOnClickListener {
            openHome()
            refreshNetworkIp()
            refreshHomeState()
        }

        findViewById<android.view.View>(R.id.sharedNavJournal).setOnClickListener {
            openJournal()
        }

        homePage.findViewById<android.view.View>(R.id.navIpFinder).setOnClickListener {
            startActivity(Intent(this, IpFinderActivity::class.java))
        }

        homePage.findViewById<android.view.View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        homePage.findViewById<android.view.View>(R.id.buttonMenu).setOnClickListener {
            showSideMenu()
        }

        journalUiBinder = JournalUiBinder(
            this,
            journalPage.findViewById(R.id.journalList)
        )
        journalPage.findViewById<android.view.View>(R.id.journalBackButton)
            .setOnClickListener { openHome() }
        journalPage.findViewById<MaterialButton>(R.id.clearJournalButton)
            .setOnClickListener {
                AppLogStore.clear(this)
                journalUiBinder.refresh()
            }
        homeJournalPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                    updateBottomNavigation(
                        if (position == PAGE_JOURNAL || positionOffset >= 0.5f) {
                            PAGE_JOURNAL
                        } else {
                            PAGE_HOME
                        }
                    )
                }

                override fun onPageSelected(position: Int) {
                    updateBottomNavigation(position)
                    if (position == PAGE_JOURNAL) {
                        journalUiBinder.refresh()
                    }
                }
            }
        )
        updateBottomNavigation(PAGE_HOME)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (homeJournalPager.currentItem == PAGE_JOURNAL) {
                        openHome()
                    } else {
                        finish()
                    }
                }
            }
        )
        journalUiBinder.refresh()

    }

    override fun onStart() {
        super.onStart()
        if (journalLogListener == null) {
            journalLogListener = AppLogStore.registerChangeListener(this) {
                runOnUiThread {
                    if (
                        ::journalUiBinder.isInitialized &&
                        homeJournalPager.currentItem == PAGE_JOURNAL
                    ) {
                        journalUiBinder.refresh()
                    }
                }
            }
        }
        if (!networkCallbackRegistered) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    override fun onStop() {
        journalLogListener?.let {
            AppLogStore.unregisterChangeListener(this, it)
        }
        journalLogListener = null
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
            refreshHomeState()
            if (::journalUiBinder.isInitialized && homeJournalPager.currentItem == PAGE_JOURNAL) {
                journalUiBinder.refresh()
            }
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
            saveSelectedNetwork(network)
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
                stopConnectionTimerIfInactive(result.value.connection)
            }

            is HomeControllerResult.Message -> {
                val currentState = homeController?.currentState()
                currentState?.let {
                    uiBinder.showAccess(it.access)
                    timerController.syncAccessRemaining(it.access.remainingSeconds)
                    uiBinder.showConnection(it.connection)
                    updatePowerButtonState(it.connection)
                    stopConnectionTimerIfInactive(it.connection)
                }
                val userMessage = userFacingMessage(result.text)
                val pendingConnection =
                    currentState?.connection is HomeConnectionState.Connecting &&
                        userMessage.startsWith("Connexion en cours")
                if (!pendingConnection) {
                    AppLogStore.add(this, "Information • $userMessage")
                    Toast.makeText(
                        this,
                        userMessage,
                        Toast.LENGTH_LONG
                    ).show()
                }
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
        runOnUiThread {
            val isConnectedPulse = durationMs >= CONNECTED_VIBRATION_MS
            val feedbackType = if (isConnectedPulse) {
                HapticFeedbackConstants.LONG_PRESS
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            powerButton.performHapticFeedback(
                feedbackType,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )

            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) {
                AppLogStore.add(this, "Vibration indisponible sur cet appareil.")
                return@runOnUiThread
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        VibrationEffect.createPredefined(
                            if (isConnectedPulse) {
                                VibrationEffect.EFFECT_HEAVY_CLICK
                            } else {
                                VibrationEffect.EFFECT_CLICK
                            }
                        )
                    } else {
                        VibrationEffect.createOneShot(durationMs, MAX_VIBRATION_AMPLITUDE)
                    }
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build()
                    vibrator.vibrate(effect, attributes)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }.onFailure {
                AppLogStore.add(this, "Vibration impossible • ${it.message ?: "erreur Android"}.")
            }
        }
    }

    private fun stopConnectionTimerIfInactive(state: HomeConnectionState) {
        if (state !is HomeConnectionState.Connected && state !is HomeConnectionState.Connecting) {
            timerController.stopConnectionTimer()
        }
    }

    private fun openJournal() {
        journalUiBinder.refresh()
        homeJournalPager.setCurrentItem(PAGE_JOURNAL, true)
    }

    private fun openHome() {
        homeJournalPager.setCurrentItem(PAGE_HOME, true)
    }

    private fun copyNetworkIp() {
        val ipAddress = networkIpValue.text.toString().trim()
        if (
            ipAddress.isBlank() ||
            ipAddress.equals("Indisponible", ignoreCase = true) ||
            ipAddress == "—"
        ) {
            Toast.makeText(this, "Adresse IP indisponible.", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Adresse IP du réseau", ipAddress)
        )
        AppLogStore.add(this, "Adresse IP copiée.")
        Toast.makeText(this, "Adresse IP copiée.", Toast.LENGTH_SHORT).show()
    }

    private fun userFacingMessage(value: String): String {
        val hasTechnicalConnectionDetail = TECHNICAL_CONNECTION_TERMS.containsMatchIn(value) ||
            value.contains("out of range", ignoreCase = true)
        return if (hasTechnicalConnectionDetail) {
            "Connexion en cours. Appuie sur le bouton pour arrêter puis réessaie."
        } else {
            value
        }
    }

    private fun loadSelectedNetwork(): NetworkOption {
        val savedId = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(PREF_SELECTED_NETWORK, null)
        return NetworkOption.ALL.firstOrNull { it.id == savedId }
            ?: NetworkOption.ALL.first()
    }

    private fun saveSelectedNetwork(network: NetworkOption) {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_SELECTED_NETWORK, network.id)
            .apply()
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
                openJournal()
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
        private const val PREFERENCES_NAME = "barka_home_preferences"
        private const val PREF_SELECTED_NETWORK = "selected_network_id"
        private const val PRESS_VIBRATION_MS = 80L
        private const val CONNECTED_VIBRATION_MS = 160L
        private const val MAX_VIBRATION_AMPLITUDE = 255
        private const val PAGE_HOME = 0
        private const val PAGE_JOURNAL = 1
        private val TECHNICAL_CONNECTION_TERMS = Regex(
            "(?i)\\b(vless|slowdns|udp|c6|tun2socks|xray|dnstt|socks|udpgw|port)\\b"
        )
    }
}
