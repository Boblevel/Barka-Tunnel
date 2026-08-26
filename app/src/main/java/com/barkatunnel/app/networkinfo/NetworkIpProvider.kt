package com.barkatunnel.app.networkinfo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.barkatunnel.app.R
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkIpProvider {

    data class NetworkIpInfo(
        val ip: String,
        val transport: String,
        val transportType: NetworkTransport
    )

    fun getCurrent(context: Context): NetworkIpInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }

        val transportType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                NetworkTransport.CELLULAR
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                NetworkTransport.WIFI
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ->
                NetworkTransport.VPN
            else -> NetworkTransport.OTHER
        }
        val transport = when (transportType) {
            NetworkTransport.CELLULAR -> context.getString(R.string.network_transport_mobile)
            NetworkTransport.WIFI -> context.getString(R.string.network_transport_wifi)
            NetworkTransport.VPN -> context.getString(R.string.network_transport_vpn)
            NetworkTransport.OTHER -> context.getString(R.string.network_transport_network)
        }

        val activeIp = active
            ?.let { cm.getLinkProperties(it) }
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress

        val ip = activeIp ?: findIpv4() ?: context.getString(R.string.unavailable)
        return NetworkIpInfo(
            ip = ip,
            transport = transport,
            transportType = transportType
        )
    }

    fun getCellularIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.asSequence()
            .mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return@mapNotNull null
                }
                cm.getLinkProperties(network)
                    ?.linkAddresses
                    ?.asSequence()
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            }
            .firstOrNull()
    }

    private fun findIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()

            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
