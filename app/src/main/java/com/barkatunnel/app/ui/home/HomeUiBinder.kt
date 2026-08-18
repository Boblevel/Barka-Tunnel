package com.barkatunnel.app.ui.home

import android.widget.TextView
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton

class HomeUiBinder(
    private val networkName: TextView,
    private val networkSubtitle: TextView,
    private val vpnStatus: TextView,
    private val connectionTime: TextView,
    private val accessRemainingTime: TextView,
    private val accessStatus: TextView,
    private val connectButton: MaterialButton
) {

    fun showNetwork(network: NetworkOption?) {
        if (network == null) {
            networkName.text = "CHOISIR LE RÉSEAU"
            networkSubtitle.text =
                "MOOV-AFRICA BF 🇧🇫  •  ORANGE BF 🇧🇫  •  TELECEL BF 🇧🇫"
            return
        }

        networkName.text = network.displayName
        networkSubtitle.text = "Réseau sélectionné"
    }

    fun showAccess(state: HomeAccessState) {
        accessRemainingTime.text =
            ConnectionTimeFormatter.format(state.remainingSeconds)

        accessStatus.text = state.label
    }

    fun showConnection(state: HomeConnectionState) {
        when (state) {
            HomeConnectionState.Disconnected -> {
                vpnStatus.text = "NON CONNECTÉ"
                connectButton.text = "SE CONNECTER"
            }

            HomeConnectionState.Connecting -> {
                vpnStatus.text = "CONNEXION..."
                connectButton.text = "CONNEXION..."
            }

            is HomeConnectionState.Connected -> {
                vpnStatus.text = "CONNECTÉ"
                connectButton.text = "SE DÉCONNECTER"
            }

            is HomeConnectionState.Error -> {
                vpnStatus.text = "ERREUR"
                connectButton.text = "RÉESSAYER"
            }
        }
    }

    fun showConnectionTime(seconds: Long) {
        connectionTime.text =
            "Temps de connexion : ${ConnectionTimeFormatter.format(seconds)}"
    }
}
