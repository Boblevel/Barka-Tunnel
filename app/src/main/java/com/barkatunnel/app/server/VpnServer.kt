package com.barkatunnel.app.server

data class VpnServer(
    val id: String,
    val name: String,
    val country: String,
    val city: String?,
    val online: Boolean
)
