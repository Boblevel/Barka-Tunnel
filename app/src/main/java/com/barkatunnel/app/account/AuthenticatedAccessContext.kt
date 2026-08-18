package com.barkatunnel.app.account

data class AuthenticatedAccessContext(
    val accountId: String,
    val token: String,
    val deviceId: String
)
