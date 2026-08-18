package com.barkatunnel.app.access

data class AccessCheckRequest(
    val accountId: String,
    val deviceId: String
)
