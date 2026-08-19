package com.barkatunnel.app

// BARKA_HOME_RUNTIME_V5_FINAL_NAV_NO_LOGIN

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.guide.GuideActivity
import com.barkatunnel.app.ipfinder.IpFinderActivity
import com.barkatunnel.app.journal.AppLogStore
import com.barkatunnel.app.journal.JournalActivity
import com.barkatunnel.app.networkinfo.MoovIpValidator
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
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var selectedNetwork: NetworkOption? = NetworkOption.ALL.firstOrNull()
    private var homeController: HomeController? = null

    private lateinit var networkIpValue: TextView
    private lateinit var networkIpStatus: TextView
    private lateinit var networkName: TextView
    private lateinit var networkSubtitle: TextView
    private lateinit var accessRemainingTime: TextView
    private lateinit var accessStatus: TextView
    private lateinit var connectionTime: TextView
    private lateinit var vpnStatus: TextView
    private lateinit var connectButton: MaterialButton

    private lateinit var uiBinder: HomeUiBinder
    private lateinit var timerController: HomeTimerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val networkSelector = findViewById<android.view.View>(R.id.networkSelector)
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

            val controller = requireController() ?: return@setOnClickListener

            runHomeAction(
                successMessage = "Serveurs et accès actualisés."
            ) {
                val serverResult = controller.refreshServers()

                if (serverResult is HomeControllerResult.Message) {
                    return@runHomeAction serverResult
                }

                val accessResult = controller.refreshAccess()

                if (accessResult is HomeControllerResult.State) {
                    timerController.syncAccessRemaining(
                        accessResult.value.access.remainingSeconds
                    )
                }

                accessResult
            }
        }

        val connectAction = {
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

        val defaultNetwork = NetworkOption.ALL.firstOrNull()
        if (defaultNetwork != null && homeController?.currentState()?.selectedNetwork == null) {
            handleHomeResult(homeController!!.selectNetwork(defaultNetwork))
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
        val options = NetworkOption.ALL
        val labels = options
            .map { it.displayName }
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choisir le réseau")
            .setItems(labels) { _, which ->
                val network = options[which]
                selectedNetwork = network
                AppLogStore.add(this, "Réseau sélectionné • ${network.displayName}.")

                val controller = homeController

                if (controller != null) {
                    handleHomeResult(
                        controller.selectNetwork(network)
                    )
                } else {
                    uiBinder.showNetwork(network)
                }

                refreshNetworkIp()
            }
            .setNegativeButton("Annuler", null)
            .show()
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

    private fun refreshNetworkIp() {
        val info = NetworkIpProvider.getCurrent(this)

        networkIpValue.text = info.ip

        val transportLabel = info.transport

        networkIpStatus.text = when (selectedNetwork?.id) {
            "moov_bf" -> {
                if (MoovIpValidator.isCompatible(info.ip)) {
                    "$transportLabel • IP compatible MOOV"
                } else {
                    "$transportLabel • IP MOOV non compatible"
                }
            }

            "orange_bf" ->
                "$transportLabel • Vérification Orange via IP Finder"

            "telecel_bf" ->
                "$transportLabel • IP actuelle"

            else ->
                "$transportLabel • IP actuelle"
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
}
