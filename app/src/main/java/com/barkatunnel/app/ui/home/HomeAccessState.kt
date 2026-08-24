package com.barkatunnel.app.ui.home

data class HomeAccessState(
    val allowed: Boolean = false,
    val remainingSeconds: Long = 0L,
    val label: String = "Aucun temps actif",
    val notice: String? = null
)
