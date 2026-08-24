package com.barkatunnel.app.ui.home

import android.widget.TextView
import androidx.core.content.ContextCompat
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
                "MOOV-AFRICA BF  •  ORANGE BF  •  TELECEL BF"
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
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_text)
                )
            }

            HomeConnectionState.Connecting -> {
                vpnStatus.text = "CONNEXION…"
                connectButton.text = "SE DÉCONNECTER"
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_blue)
                )
            }

            is HomeConnectionState.Connected -> {
                vpnStatus.text = "CONNECTÉ"
                connectButton.text = "SE DÉCONNECTER"
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_green)
                )
            }

            is HomeConnectionState.Error -> {
                vpnStatus.text = "CONNEXION…"
                connectButton.text = "SE DÉCONNECTER"
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_blue)
                )
            }
        }
    }

    fun showConnectionTime(seconds: Long) {
        connectionTime.text =
            "Temps de connexion : ${ConnectionTimeFormatter.format(seconds)}"
    }
}
