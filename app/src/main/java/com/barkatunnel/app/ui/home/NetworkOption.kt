package com.barkatunnel.app.ui.home

data class NetworkOption(
    val id: String,
    val displayName: String
) {
    companion object {
        val ALL = listOf(
            NetworkOption("moov_bf", "MOOV-AFRICA BF"),
            NetworkOption("moov_tiktok", "MOOV FORFAIT TIKTOK"),
            NetworkOption("orange_bf", "ORANGE BF"),
            NetworkOption("telecel_bf", "TELECEL BF"),
            NetworkOption("global_world", "GLOBAL MONDIAL")
        )
    }
}
