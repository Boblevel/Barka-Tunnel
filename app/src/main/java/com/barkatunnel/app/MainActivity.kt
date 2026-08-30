package com.barkatunnel.app

// BARKA_HOME_RUNTIME_V5_FINAL_NAV_NO_LOGIN

import android.Manifest
import android.app.ActivityManager
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
import android.net.Uri
import android.net.NetworkCapabilities
import android.net.VpnService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.barkatunnel.app.BuildConfig
import com.barkatunnel.app.guide.GuideActivity
import com.barkatunnel.app.ipfinder.IpFinderActivity
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.journal.JournalUiBinder
import com.barkatunnel.app.networkinfo.NetworkIpProvider
import com.barkatunnel.app.networkinfo.NetworkTransport
import com.barkatunnel.app.settings.SettingsActivity
import com.barkatunnel.app.subscription.ActivationActivity
import com.barkatunnel.app.subscription.SubscriptionActivity
import com.barkatunnel.app.support.SupportActivity
import com.barkatunnel.app.ui.home.HomeAccessSnapshotStore
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private lateinit var networkFlagNiger: ImageView
    private lateinit var networkFlagTogo: ImageView
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
    private val profileSyncInProgress = AtomicBoolean(false)
    private val initialSyncInProgress = AtomicBoolean(false)
    private val accessRefreshInProgress = AtomicBoolean(false)
    private val connectionOperationGeneration = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initialSyncRetryRunnable = Runnable { ensureInitialRemoteSync() }
    private var pendingConnectAfterInitialSync = false
    private var pendingConnectAfterVpnPermission = false
    @Volatile private var connectionStartRequested = false
    @Volatile private var disconnectRequested = false
    private var lastConnectActionAtElapsedMs = 0L
    private var orangeIpWarningToast: Toast? = null

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
                R.string.notification_permission_help,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val updateNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLogStore.add(
            this,
            if (granted) {
                "Permission de notification des mises à jour accordée."
            } else {
                "Permission de notification des mises à jour refusée."
            }
        )
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val wasPending = pendingConnectAfterVpnPermission
        pendingConnectAfterVpnPermission = false
        if (result.resultCode == RESULT_OK && wasPending) {
            AppLogStore.add(this, "Permission VPN Android accordée.")
            val controller = requireController()
            if (controller != null) {
                startVpnConnection(controller)
            }
        } else {
            AppLogStore.add(this, "Permission VPN Android refusée.")
            resetPendingConnectionState()
            Toast.makeText(
                this,
                R.string.vpn_permission_required,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val connectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetworkIpAsync()
            ensureInitialRemoteSync()
            maybeSyncVpnProfiles()
        }
        override fun onLost(network: Network) = refreshNetworkIpAsync()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshNetworkIpAsync()
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                ensureInitialRemoteSync()
            }
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                if (isInitialRemoteSyncComplete()) {
                    refreshVpnServices(force = true)
                    if (::updateCoordinator.isInitialized) {
                        updateCoordinator.check(showNoUpdate = false, force = true)
                    }
                } else {
                    ensureInitialRemoteSync()
                }
            }
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshNetworkIpAsync()
    }

    private val vpnProfileRepository by lazy {
        VpnProfileRepository(BarkaBackendClient(this), this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyRecentsTaskIcon()
        setContentView(R.layout.activity_home_journal_pager)
        applyRootSystemInsets()
        val homePage = layoutInflater.inflate(R.layout.activity_main, null, false)
        val journalPage = layoutInflater.inflate(R.layout.activity_journal, null, false)
        journalPage.findViewById<android.view.View>(R.id.journalContentContainer).apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        }
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
        homeJournalPager.post { configureFirstLaunchChannelBanner() }
        applySystemBars()
        updateCoordinator = AppUpdateCoordinator(this)
        selectedNetwork = loadSelectedNetwork()

        val networkSelector = homePage.findViewById<android.view.View>(R.id.networkSelector)
        networkLogo = homePage.findViewById(R.id.networkLogo)
        networkName = homePage.findViewById(R.id.networkName)
        networkSubtitle = homePage.findViewById(R.id.networkSubtitle)
        networkFlagNiger = homePage.findViewById(R.id.networkFlagNiger)
        networkFlagTogo = homePage.findViewById(R.id.networkFlagTogo)

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

                if (seconds <= 0L) {
                    accessStatus.setText(R.string.no_active_time)
                }
            },
            onConnectionTick = { seconds ->
                connectionTime.text = getString(
                    R.string.connection_time_format,
                    formatDuration(seconds)
                )
            }
        )

        timerController.start()
        AppLogStore.add(this, "Barka Tunnel démarré.")
        refreshNetworkIp()
        initializeHomeRuntime()
        requestUpdateNotificationPermissionIfNeeded()
        ensureInitialRemoteSync()

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
            refreshVpnServices(force = true)
            updateCoordinator.check(showNoUpdate = true, force = true)

            val controller = requireController() ?: return@setOnClickListener

            runHomeAction(
                successMessage = getString(R.string.access_refreshed)
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
            val now = SystemClock.elapsedRealtime()
            if (now - lastConnectActionAtElapsedMs < CONNECT_ACTION_DEBOUNCE_MS) {
                return@connectAction
            }
            lastConnectActionAtElapsedMs = now

            orangeIpWarningToast?.cancel()
            orangeIpWarningToast = null

            val controller = requireController() ?: return@connectAction

            if (disconnectRequested) {
                return@connectAction
            }

            if (pendingConnectAfterInitialSync || pendingConnectAfterVpnPermission) {
                vibrateOnce(PRESS_VIBRATION_MS)
                cancelPendingConnectionStart(controller)
                return@connectAction
            }

            val currentConnection =
                syncVpnRuntimeState() ?: controller.currentState().connection

            when (currentConnection) {
                HomeConnectionState.Disconnected -> {
                    vibrateOnce(PRESS_VIBRATION_MS)
                    beginVpnConnection(controller)
                }
                HomeConnectionState.Disconnecting -> Unit
                HomeConnectionState.Connecting,
                is HomeConnectionState.Connected,
                is HomeConnectionState.Error -> {
                    vibrateOnce(PRESS_VIBRATION_MS)
                    requestVpnDisconnect(controller)
                }
            }
        }

        connectButton.setOnClickListener {
            connectAction()
        }
        powerButton.setOnClickListener {
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
        ensureInitialRemoteSync()
        maybeSyncVpnProfiles()
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
        mainHandler.removeCallbacks(initialSyncRetryRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()

        if (::networkIpValue.isInitialized) {
            applySystemBars()
            refreshNetworkIp()
            if (disconnectRequested) {
                homeController?.let {
                    handleHomeResult(it.syncConnection(HomeConnectionState.Disconnecting))
                }
            } else if (
                pendingConnectAfterInitialSync ||
                pendingConnectAfterVpnPermission ||
                connectionStartRequested
            ) {
                homeController?.let {
                    handleHomeResult(it.syncConnection(HomeConnectionState.Connecting))
                }
            } else {
                syncVpnRuntimeState()
            }
            if (
                BarkaVpnService.connectionSnapshot().state ==
                    BarkaVpnService.RuntimeConnectionState.DISCONNECTED &&
                !pendingConnectAfterInitialSync &&
                !pendingConnectAfterVpnPermission &&
                !connectionStartRequested
            ) {
                BarkaVpnService.cancelConnectingNotification(this)
            }
            refreshHomeState()
            if (isInitialRemoteSyncComplete()) {
                refreshVpnServices(force = true)
                if (::updateCoordinator.isInitialized) {
                    updateCoordinator.check(showNoUpdate = false, force = true)
                }
            } else {
                ensureInitialRemoteSync()
            }
            if (::journalUiBinder.isInitialized && homeJournalPager.currentItem == PAGE_JOURNAL) {
                journalUiBinder.refresh()
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(initialSyncRetryRunnable)
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
        val cachedAccess = HomeAccessSnapshotStore.restore(this)
        if (cachedAccess != null) {
            handleHomeResult(homeController!!.syncAccess(cachedAccess))
            timerController.syncAccessRemaining(cachedAccess.remainingSeconds)
        } else {
            accessStatus.setText(R.string.waiting_activation)
        }

        val initialNetwork = selectedNetwork
            ?: NetworkOption.ALL.firstOrNull()
        if (initialNetwork != null && homeController?.currentState()?.selectedNetwork == null) {
            handleHomeResult(homeController!!.selectNetwork(initialNetwork))
        }

        syncVpnRuntimeState()
        refreshHomeState()
    }

    private fun syncVpnRuntimeState(): HomeConnectionState? {
        val controller = homeController ?: return null
        val snapshot = BarkaVpnService.connectionSnapshot()
        val connection = when {
            disconnectRequested &&
                snapshot.state != BarkaVpnService.RuntimeConnectionState.DISCONNECTED ->
                HomeConnectionState.Disconnecting
            connectionStartRequested &&
                snapshot.state == BarkaVpnService.RuntimeConnectionState.DISCONNECTED ->
                HomeConnectionState.Connecting
            snapshot.state == BarkaVpnService.RuntimeConnectionState.DISCONNECTED ->
                HomeConnectionState.Disconnected
            snapshot.state == BarkaVpnService.RuntimeConnectionState.CONNECTING ->
                HomeConnectionState.Connecting
            snapshot.state == BarkaVpnService.RuntimeConnectionState.CONNECTED ->
                HomeConnectionState.Connected(
                    snapshot.profileName ?: controller.currentState().selectedNetwork?.displayName.orEmpty()
                )
            else -> HomeConnectionState.Disconnecting
        }

        if (snapshot.state == BarkaVpnService.RuntimeConnectionState.CONNECTED) {
            connectionStartRequested = false
            disconnectRequested = false
        } else if (snapshot.state == BarkaVpnService.RuntimeConnectionState.DISCONNECTED && disconnectRequested) {
            disconnectRequested = false
        }

        handleHomeResult(controller.syncConnection(connection))

        if (connection is HomeConnectionState.Connected) {
            val elapsedSeconds = if (snapshot.connectedAtElapsedMs > 0L) {
                ((SystemClock.elapsedRealtime() - snapshot.connectedAtElapsedMs) / 1000L)
                    .coerceAtLeast(0L)
            } else {
                0L
            }
            timerController.startConnectionTimer(elapsedSeconds)
        }

        return connection
    }

    private fun refreshHomeState() {
        val controller = homeController ?: return
        if (!accessRefreshInProgress.compareAndSet(false, true)) return

        Thread {
            val accessResult = runCatching { controller.refreshAccess() }.getOrNull()

            runOnUiThread {
                try {
                    if (accessResult != null) {
                        handleHomeResult(accessResult)

                        if (accessResult is HomeControllerResult.State) {
                            timerController.syncAccessRemaining(
                                accessResult.value.access.remainingSeconds
                            )
                        }
                    }
                } finally {
                    accessRefreshInProgress.set(false)
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
        val nameMoov = dialog.findViewById<TextView>(R.id.optionMoovName)
        val nameOrange = dialog.findViewById<TextView>(R.id.optionOrangeName)
        val nameTelecel = dialog.findViewById<TextView>(R.id.optionTelecelName)

        fun showAvailability(name: TextView, networkId: String, label: String) {
            name.text = if (vpnProfileRepository.isMaintenance(networkId)) {
                "$label\n${getString(R.string.network_maintenance_badge)}"
            } else {
                label
            }
        }
        showAvailability(nameMoov, "moov_bf", "MOOV-AFRICA BF")
        showAvailability(nameOrange, "orange_bf", "ORANGE BF")
        showAvailability(nameTelecel, "telecel_bf", "TELECEL BF")

        checkMoov.text = if (selectedId == "moov_bf") "✓" else "○"
        checkOrange.text = if (selectedId == "orange_bf") "✓" else "○"
        checkTelecel.text = if (selectedId == "telecel_bf") "✓" else "○"

        fun select(networkId: String) {
            val network = NetworkOption.ALL.firstOrNull { it.id == networkId }
                ?: return
            if (vpnProfileRepository.isMaintenance(networkId)) {
                Toast.makeText(this, R.string.network_maintenance_message, Toast.LENGTH_LONG).show()
                return
            }

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
            if (network.id == "orange_bf") {
                showOrangeIpWarningIfNeeded()
            }
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

    private fun showOrangeIpWarningIfNeeded() {
        orangeIpWarningToast?.cancel()
        orangeIpWarningToast = null

        if (NetworkIpProvider.getCurrent(this).transportType != NetworkTransport.CELLULAR) {
            return
        }

        val currentCellularIp = NetworkIpProvider.getCellularIpv4(this)
        if (IpFinderActivity.isIpCompatible(this, currentCellularIp)) {
            return
        }

        orangeIpWarningToast = Toast.makeText(
            this,
            R.string.orange_ipfinder_required,
            Toast.LENGTH_SHORT
        ).also { it.show() }
    }

    private fun beginVpnConnection(controller: HomeController) {
        connectionOperationGeneration.incrementAndGet()
        disconnectRequested = false
        connectionStartRequested = true
        val selected = controller.currentState().selectedNetwork
        AppLogStore.add(
            this,
            "Connexion en cours${selected?.let { " • ${it.displayName}" } ?: ""}."
        )
        handleHomeResult(controller.syncConnection(HomeConnectionState.Connecting))

        if (!isInitialRemoteSyncComplete()) {
            pendingConnectAfterInitialSync = true
            ensureInitialRemoteSync()
            return
        }

        continueVpnConnection(controller)
    }

    private fun continueVpnConnection(controller: HomeController) {
        if (updateCoordinator.showBlockingIfNeeded()) {
            resetPendingConnectionState()
            return
        }

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            pendingConnectAfterVpnPermission = true
            handleHomeResult(controller.syncConnection(HomeConnectionState.Connecting))
            AppLogStore.add(this, "Demande de permission VPN Android.")
            vpnPermissionLauncher.launch(permissionIntent)
            return
        }

        startVpnConnection(controller)
    }

    private fun startVpnConnection(controller: HomeController) {
        pendingConnectAfterInitialSync = false
        pendingConnectAfterVpnPermission = false
        val operationGeneration = connectionOperationGeneration.get()
        recordConnectionAttemptAsync()

        handleHomeResult(controller.syncConnection(HomeConnectionState.Connecting))

        Thread {
            val result = controller.connect()
            runOnUiThread {
                if (
                    operationGeneration != connectionOperationGeneration.get() ||
                    disconnectRequested
                ) {
                    syncVpnRuntimeState()
                    return@runOnUiThread
                }

                val connectedState =
                    (result as? HomeControllerResult.State)?.value?.connection
                if (controller.currentState().connection !is HomeConnectionState.Connecting) {
                    connectionStartRequested = false
                }

                if (connectedState is HomeConnectionState.Connected) {
                    timerController.startConnectionTimer()
                    vibrateOnce(CONNECTED_VIBRATION_MS)
                    AppLogStore.add(
                        this,
                        "VPN connecté • ${connectedState.networkName}."
                    )
                } else if (connectedState is HomeConnectionState.Disconnected) {
                    BarkaVpnService.cancelConnectingNotification(this)
                }

                handleHomeResult(result)
            }
        }.start()
    }

    private fun requestVpnDisconnect(controller: HomeController) {
        val operationGeneration = connectionOperationGeneration.incrementAndGet()
        pendingConnectAfterInitialSync = false
        pendingConnectAfterVpnPermission = false
        connectionStartRequested = false
        disconnectRequested = true
        AppLogStore.add(this, "Déconnexion en cours.")
        handleHomeResult(controller.syncConnection(HomeConnectionState.Disconnecting))

        Thread {
            val result = controller.disconnect()
            runOnUiThread {
                if (operationGeneration != connectionOperationGeneration.get()) {
                    syncVpnRuntimeState()
                    return@runOnUiThread
                }

                val snapshot = BarkaVpnService.connectionSnapshot()
                if (
                    result is HomeControllerResult.State &&
                    snapshot.state == BarkaVpnService.RuntimeConnectionState.DISCONNECTED
                ) {
                    timerController.stopConnectionTimer()
                    syncVpnRuntimeState()
                } else {
                    handleHomeResult(result)
                    syncVpnRuntimeState()
                }
            }
        }.start()
    }

    private fun cancelPendingConnectionStart(controller: HomeController) {
        connectionOperationGeneration.incrementAndGet()
        pendingConnectAfterInitialSync = false
        pendingConnectAfterVpnPermission = false
        connectionStartRequested = false
        disconnectRequested = true
        BarkaVpnService.cancelConnectingNotification(this)
        AppLogStore.add(this, "Déconnexion en cours.")
        handleHomeResult(controller.syncConnection(HomeConnectionState.Disconnecting))
        mainHandler.postDelayed({
            if (disconnectRequested) {
                disconnectRequested = false
                handleHomeResult(controller.syncConnection(HomeConnectionState.Disconnected))
                AppLogStore.add(this, "VPN déconnecté.")
            }
        }, DISCONNECT_UI_SETTLE_MS)
    }

    private fun resetPendingConnectionState() {
        pendingConnectAfterInitialSync = false
        pendingConnectAfterVpnPermission = false
        connectionStartRequested = false
        disconnectRequested = false
        val controller = homeController ?: return
        val runtimeState = BarkaVpnService.connectionSnapshot().state
        if (runtimeState == BarkaVpnService.RuntimeConnectionState.DISCONNECTED) {
            handleHomeResult(controller.syncConnection(HomeConnectionState.Disconnected))
        }
    }

    private fun recordConnectionAttemptAsync() {
        Thread {
            runCatching {
                BarkaBackendClient(applicationContext).markConnectionAttempt()
            }
        }.start()
    }

    private fun requireController(): HomeController? {
        val controller = homeController

        if (controller != null) {
            return controller
        }

        AppLogStore.add(this, "Contrôleur VPN indisponible.")
        Toast.makeText(
            this,
            R.string.vpn_initializing,
            Toast.LENGTH_SHORT
        ).show()

        initializeHomeRuntime()
        return homeController
    }

    private fun runHomeAction(
        successMessage: String? = null,
        disableConnectButton: Boolean = true,
        action: () -> HomeControllerResult
    ) {
        if (disableConnectButton) {
            connectButton.isEnabled = false
        }

        Thread {
            val result = action()

            runOnUiThread {
                if (disableConnectButton) {
                    connectButton.isEnabled = true
                }
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
                val latestState = homeController?.currentState() ?: result.value
                selectedNetwork = latestState.selectedNetwork

                uiBinder.showNetwork(
                    latestState.selectedNetwork
                )
                updateSelectedNetworkLogo(
                    latestState.selectedNetwork
                )

                uiBinder.showAccess(
                    latestState.access
                )

                uiBinder.showConnection(
                    latestState.connection
                )
                updatePowerButtonState(latestState.connection)
                stopConnectionTimerIfInactive(latestState.connection)
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
                        userMessage.startsWith(
                            getString(R.string.journal_connecting_title)
                        )
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
                accessStatus.setText(R.string.activate_access_short)
                Toast.makeText(
                    this,
                    R.string.activate_access_long,
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
        if (!::powerButton.isInitialized || isFinishing || isDestroyed) return
        runOnUiThread {
            if (!::powerButton.isInitialized || isFinishing || isDestroyed) {
                return@runOnUiThread
            }
            runCatching {
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
                    return@runCatching
                }

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
            ipAddress.equals(getString(R.string.unavailable), ignoreCase = true) ||
            ipAddress.equals("Indisponible", ignoreCase = true) ||
            ipAddress == getString(R.string.network_ip_offline) ||
            ipAddress == "—"
        ) {
            Toast.makeText(this, R.string.ip_unavailable_toast, Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.ip_clipboard_label), ipAddress)
        )
        AppLogStore.add(this, "Adresse IP copiée.")
        Toast.makeText(this, R.string.ip_copied_toast, Toast.LENGTH_SHORT).show()
    }

    private fun userFacingMessage(value: String): String {
        val hasTechnicalConnectionDetail = TECHNICAL_CONNECTION_TERMS.containsMatchIn(value) ||
            value.contains("out of range", ignoreCase = true)
        if (hasTechnicalConnectionDetail) {
            return getString(R.string.connection_pending_help)
        }
        return when (value) {
            "Connexion en cours. Appuie sur le bouton pour arrêter puis réessaie." ->
                getString(R.string.connection_pending_help)
            "Choisis d’abord un réseau." -> getString(R.string.choose_network_first)
            "Aucun accès actif." -> getString(R.string.no_access_active)
            "Le VPN est toujours connecté." -> getString(R.string.vpn_still_connected)
            "Déconnexion refusée." -> getString(R.string.disconnect_refused)
            "L’essai gratuit de cet appareil a déjà été utilisé." ->
                getString(R.string.trial_already_used)
            "L’essai gratuit n’est pas disponible sur cet appareil." ->
                getString(R.string.trial_not_available_device)
            else -> value
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

        dialog.findViewById<TextView>(R.id.menuVersion).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME)

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

    @Suppress("DEPRECATION")
    private fun configureFirstLaunchChannelBanner() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val currentInstallStamp = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)

        if (
            currentInstallStamp <= 0L ||
            preferences.getLong(PREF_CHANNEL_INVITE_INSTALL_STAMP, Long.MIN_VALUE) == currentInstallStamp
        ) {
            return
        }

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_channel_invite)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        fun markHandledAndClose() {
            preferences.edit()
                .putLong(PREF_CHANNEL_INVITE_INSTALL_STAMP, currentInstallStamp)
                .apply()
            dialog.dismiss()
        }

        dialog.findViewById<View>(R.id.channelInviteClose).setOnClickListener {
            markHandledAndClose()
        }
        dialog.findViewById<View>(R.id.channelInviteJoinButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL_URL))
            runCatching {
                startActivity(intent)
            }.onSuccess {
                markHandledAndClose()
            }.onFailure {
                Toast.makeText(
                    this,
                    R.string.channel_invite_open_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                dimAmount = 0.58f
                gravity = Gravity.CENTER
            }
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun applyRecentsTaskIcon() {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_barka_logo) ?: return
        val size = (72f * resources.displayMetrics.density).toInt().coerceAtLeast(72)
        val inset = (4f * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawable.setBounds(inset, inset, size - inset, size - inset)
        drawable.draw(canvas)
        setTaskDescription(
            ActivityManager.TaskDescription(
                getString(R.string.app_name),
                bitmap,
                Color.WHITE
            )
        )
    }

    private fun applyRootSystemInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.homeJournalRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun requestUpdateNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isInitialProfileSyncComplete(): Boolean =
        vpnProfileRepository.hasSuccessfulSyncForCurrentInstall()

    private fun isInitialRemoteSyncComplete(): Boolean {
        val prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        return prefs.getBoolean(PREF_INITIAL_REMOTE_SYNC_COMPLETE, false) &&
            prefs.getString(PREF_INITIAL_REMOTE_SYNC_INSTALL_ID, null) ==
                vpnProfileRepository.currentInstallId() &&
            isInitialProfileSyncComplete()
    }

    private fun ensureInitialRemoteSync() {
        if (isInitialRemoteSyncComplete()) {
            mainHandler.removeCallbacks(initialSyncRetryRunnable)
            resumePendingConnectionAfterInitialSync()
            return
        }
        if (!::updateCoordinator.isInitialized) return
        if (!initialSyncInProgress.compareAndSet(false, true)) return

        mainHandler.removeCallbacks(initialSyncRetryRunnable)
        if (isInitialProfileSyncComplete()) {
            checkInitialAppUpdate()
            return
        }

        AppLogStore.add(this, "Synchronisation des profils…")
        Thread {
            when (val sync = vpnProfileRepository.refreshCatalog()) {
                is com.barkatunnel.app.vpnprofile.VpnProfileSyncResult.Success -> {
                    AppLogStore.add(
                        this,
                        if (sync.updatedCount > 0) "Profils mis à jour." else "Profils à jour."
                    )
                    runOnUiThread {
                        selectedNetwork?.let { selected ->
                            networkSubtitle.text = if (vpnProfileRepository.isMaintenance(selected.id)) {
                                getString(R.string.network_maintenance_badge)
                            } else {
                                getString(R.string.selected_network_subtitle)
                            }
                        }
                        checkInitialAppUpdate()
                    }
                }

                is com.barkatunnel.app.vpnprofile.VpnProfileSyncResult.Error -> {
                    AppLogStore.add(
                        this,
                        "Initialisation Internet reportée • ${sync.message}"
                    )
                    initialSyncInProgress.set(false)
                    runOnUiThread { scheduleInitialRemoteSyncRetry() }
                }
            }
        }.start()
    }

    private fun checkInitialAppUpdate() {
        AppLogStore.add(this, "Vérification des mises à jour…")
        updateCoordinator.check(
            showNoUpdate = false,
            force = true
        ) { update ->
            initialSyncInProgress.set(false)
            if (update == null) {
                scheduleInitialRemoteSyncRetry()
                return@check
            }

            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_INITIAL_REMOTE_SYNC_COMPLETE, true)
                .putString(
                    PREF_INITIAL_REMOTE_SYNC_INSTALL_ID,
                    vpnProfileRepository.currentInstallId()
                )
                .apply()
            mainHandler.removeCallbacks(initialSyncRetryRunnable)
            if (!update.updateAvailable) {
                Toast.makeText(
                    this,
                    R.string.startup_sync_current,
                    Toast.LENGTH_SHORT
                ).show()
                AppLogStore.add(this, "Barka Tunnel est à jour.")
            }
            resumePendingConnectionAfterInitialSync()
        }
    }

    private fun scheduleInitialRemoteSyncRetry() {
        if (isInitialRemoteSyncComplete() || isFinishing || isDestroyed) return
        mainHandler.removeCallbacks(initialSyncRetryRunnable)
        mainHandler.postDelayed(initialSyncRetryRunnable, INITIAL_SYNC_RETRY_DELAY_MS)
    }

    private fun resumePendingConnectionAfterInitialSync() {
        if (!pendingConnectAfterInitialSync || !isInitialRemoteSyncComplete()) return
        pendingConnectAfterInitialSync = false
        val controller = homeController ?: return
        continueVpnConnection(controller)
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

    private fun maybeSyncVpnProfiles() {
        if (!isInitialRemoteSyncComplete()) {
            ensureInitialRemoteSync()
            return
        }
        if (
            !vpnProfileRepository.hasValidatedInternet() ||
            !vpnProfileRepository.shouldRefresh(PROFILE_SYNC_INTERVAL_MS)
        ) {
            return
        }
        refreshVpnServices(force = false)
    }

    private fun refreshVpnServices(force: Boolean) {
        if (
            !vpnProfileRepository.hasValidatedInternet() ||
            (!force && !vpnProfileRepository.shouldRefresh(PROFILE_SYNC_INTERVAL_MS))
        ) {
            return
        }
        if (!profileSyncInProgress.compareAndSet(false, true)) return
        Thread {
            try {
                when (val result = vpnProfileRepository.refreshCatalog()) {
                    is com.barkatunnel.app.vpnprofile.VpnProfileSyncResult.Success -> {
                        AppLogStore.add(
                            this,
                            if (result.updatedCount > 0) "Profils mis à jour." else "Profils à jour."
                        )
                        runOnUiThread {
                            selectedNetwork?.let { selected ->
                                networkSubtitle.text = if (vpnProfileRepository.isMaintenance(selected.id)) {
                                    getString(R.string.network_maintenance_badge)
                                } else {
                                    getString(R.string.selected_network_subtitle)
                                }
                            }
                        }
                        if (result.updatedCount > 0) {
                            runOnUiThread {
                                androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle(R.string.config_update_required_title)
                                    .setMessage(R.string.config_update_applied_message)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                    }

                    is com.barkatunnel.app.vpnprofile.VpnProfileSyncResult.Error -> {
                        AppLogStore.add(
                            this,
                            "Synchronisation reportée • ${result.message}"
                        )
                    }
                }
            } finally {
                profileSyncInProgress.set(false)
            }
        }.start()
    }

    private fun refreshNetworkIp() {
        val info = NetworkIpProvider.getCurrent(this)

        networkIpValue.text = if (info.transportType == NetworkTransport.OTHER) {
            getString(R.string.network_ip_offline)
        } else {
            info.ip
        }
        networkIpStatus.text = getString(
            R.string.current_ip_status_format,
            info.transport
        )

        if (::networkTransportIcon.isInitialized) {
            when (info.transportType) {
                NetworkTransport.CELLULAR -> {
                    networkTransportIcon.setImageResource(R.drawable.ic_mobile_barka)
                    networkTransportIcon.visibility = View.VISIBLE
                }
                NetworkTransport.WIFI, NetworkTransport.VPN -> {
                    networkTransportIcon.setImageResource(R.drawable.ic_wifi_barka)
                    networkTransportIcon.visibility = View.VISIBLE
                }
                NetworkTransport.OTHER -> {
                    networkTransportIcon.visibility = View.GONE
                }
            }
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
        val showMoovCountries = network?.id == "moov_bf"
        networkFlagNiger.visibility = if (showMoovCountries) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        networkFlagTogo.visibility = if (showMoovCountries) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
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
        private const val PREF_INITIAL_REMOTE_SYNC_COMPLETE = "initial_remote_sync_complete"
        private const val PREF_INITIAL_REMOTE_SYNC_INSTALL_ID = "initial_remote_sync_install_id"
        private const val PREF_CHANNEL_INVITE_INSTALL_STAMP = "channel_invite_install_stamp"
        private const val TELEGRAM_CHANNEL_URL = "https://t.me/barkaTunnel"
        private const val PRESS_VIBRATION_MS = 80L
        private const val CONNECTED_VIBRATION_MS = 160L
        private const val MAX_VIBRATION_AMPLITUDE = 255
        private const val PAGE_HOME = 0
        private const val PAGE_JOURNAL = 1
        private const val PROFILE_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val INITIAL_SYNC_RETRY_DELAY_MS = 2_500L
        private const val DISCONNECT_UI_SETTLE_MS = 250L
        private const val CONNECT_ACTION_DEBOUNCE_MS = 1_200L
        private val TECHNICAL_CONNECTION_TERMS = Regex(
            "(?i)\\b(vless|slowdns|udp|c6|tun2socks|xray|dnstt|socks|udpgw|port)\\b"
        )
    }
}
