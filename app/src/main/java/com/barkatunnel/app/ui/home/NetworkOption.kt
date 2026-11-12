package com.barkatunnel.app.ui.home

data class NetworkOption(
    val id: String,
    val displayName: String
) {
    companion object {
        val ALL = listOf(
            NetworkOption("moov_bf", "MOOV-AFRICA BF"),
            NetworkOption("moov_bf_v2", "MOOV-AFRICA BF V2"),
            NetworkOption("moov_tiktok", "MOOV FORFAIT TIKTOK"),
            NetworkOption("orange_bf", "ORANGE BF"),
            NetworkOption("orange_bf_v2", "ORANGE BF V2"),
            NetworkOption("telecel_bf", "TELECEL BF"),
            NetworkOption("global_world", "GLOBAL MONDIAL")
        )
    }
}
