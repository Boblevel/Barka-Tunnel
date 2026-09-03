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
        val cellularIp = getCellularIpv4(context)
        if (!cellularIp.isNullOrBlank()) {
            return NetworkIpInfo(
                ip = cellularIp,
                transport = context.getString(R.string.network_transport_mobile),
                transportType = NetworkTransport.CELLULAR
            )
        }

        val active = runCatching { cm.activeNetwork }.getOrNull()
        val caps = active?.let { network ->
            runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        }

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
            ?.let { network -> runCatching { cm.getLinkProperties(network) }.getOrNull() }
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
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
        return runCatching {
            cm.allNetworks.asSequence()
                .mapNotNull { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return@mapNotNull null
                    }
                    val ip = cm.getLinkProperties(network)
                        ?.linkAddresses
                        ?.asSequence()
                        ?.map { it.address }
                        ?.filterIsInstance<Inet4Address>()
                        ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?.hostAddress
                        ?: return@mapNotNull null
                    val priority = when {
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> 2
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> 1
                        else -> 0
                    }
                    priority to ip
                }
                .maxByOrNull { it.first }
                ?.second
        }.getOrNull()
    }

    fun isTenNetworkAtLeast100(ip: String?): Boolean {
        val parts = ip?.trim()?.split('.')?.takeIf { it.size == 4 } ?: return false
        val octets = parts.mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 && octets[1] >= 100
    }

    private fun findIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()

            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
