package com.barkatunnel.app.access

data class ActivationResult(
    val success: Boolean,
    val message: String,
    val expiresAt: Long?,
    val duration: ActivationDuration?
)
