package com.barkatunnel.app.networkinfo

data class CurrentNetworkIp(
    val ip: String?,
    val transport: NetworkTransport
) {
    val displayIp: String
        get() = ip ?: "Indisponible"
}
