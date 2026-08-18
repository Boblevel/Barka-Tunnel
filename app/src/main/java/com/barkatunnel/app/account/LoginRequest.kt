package com.barkatunnel.app.account

data class LoginRequest(
    val username: String,
    val password: String,
    val deviceId: String
)
