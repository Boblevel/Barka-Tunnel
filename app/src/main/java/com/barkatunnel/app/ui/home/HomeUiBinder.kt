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
            networkName.setText(R.string.choose_network_home)
            networkSubtitle.setText(R.string.network_options)
            return
        }

        networkName.text = network.displayName
        networkSubtitle.setText(R.string.selected_network_subtitle)
    }

    fun showAccess(state: HomeAccessState) {
        showAccessRemaining(state.remainingSeconds)
        accessStatus.text = localizedAccessLabel(state.label)
    }

    fun showAccessRemaining(seconds: Long) {
        accessRemainingTime.text = if (seconds == ConnectionTimeFormatter.UNLIMITED_SECONDS)
            accessRemainingTime.context.getString(R.string.access_unlimited)
        else ConnectionTimeFormatter.formatRemaining(seconds)
        accessRemainingTime.setTextColor(ContextCompat.getColor(accessRemainingTime.context,
            if (seconds == ConnectionTimeFormatter.UNLIMITED_SECONDS) R.color.barka_unlimited else R.color.barka_text))
    }

    fun showConnection(state: HomeConnectionState) {
        when (state) {
            HomeConnectionState.Disconnected -> {
                vpnStatus.setText(R.string.status_not_connected)
                connectButton.setText(R.string.connect)
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_text)
                )
            }

            HomeConnectionState.Connecting -> {
                vpnStatus.setText(R.string.status_connecting)
                connectButton.setText(R.string.disconnect)
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_blue)
                )
            }

            HomeConnectionState.Disconnecting -> {
                vpnStatus.setText(R.string.status_disconnecting)
                connectButton.setText(R.string.disconnect)
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_blue)
                )
            }

            is HomeConnectionState.Connected -> {
                vpnStatus.setText(R.string.status_connected)
                connectButton.setText(R.string.disconnect)
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_green)
                )
            }

            is HomeConnectionState.Error -> {
                vpnStatus.setText(R.string.status_not_connected)
                connectButton.setText(R.string.connect)
                vpnStatus.setTextColor(
                    ContextCompat.getColor(vpnStatus.context, R.color.barka_text)
                )
            }
        }
    }

    fun showConnectionTime(seconds: Long) {
        connectionTime.text = vpnStatus.context.getString(
            R.string.connection_time_format,
            ConnectionTimeFormatter.format(seconds)
        )
    }

    private fun localizedAccessLabel(label: String): String = when (label) {
        "Accès actif" -> vpnStatus.context.getString(R.string.access_active)
        "Accès actif • hors ligne" ->
            vpnStatus.context.getString(R.string.access_active_offline)
        "Accès autorisé" -> vpnStatus.context.getString(R.string.access_authorized)
        "Essai gratuit actif" -> vpnStatus.context.getString(R.string.free_trial_active)
        "Essai indisponible ou expiré" ->
            vpnStatus.context.getString(R.string.trial_unavailable)
        "Aucun temps actif" -> vpnStatus.context.getString(R.string.no_active_time)
        else -> label
    }
}
