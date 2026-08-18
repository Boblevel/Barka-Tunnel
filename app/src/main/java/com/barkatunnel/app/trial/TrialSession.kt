package com.barkatunnel.app.trial

data class TrialSession(
    val status: TrialStatus,
    val serverTime: Long,
    val startedAt: Long?,
    val expiresAt: Long?,
    val remainingSeconds: Long
)
