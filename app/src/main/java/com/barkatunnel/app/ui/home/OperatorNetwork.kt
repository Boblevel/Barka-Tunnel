package com.barkatunnel.app.ui.home

data class OperatorNetwork(
    val id: String,
    val displayName: String
) {
    companion object {
        val ALL = listOf(
            OperatorNetwork("moov_bf", "MOOV-AFRICA BF"),
            OperatorNetwork("moov_bf_v2", "MOOV-AFRICA BF V2"),
            OperatorNetwork("moov_tiktok", "MOOV FORFAIT TIKTOK"),
            OperatorNetwork("orange_bf", "ORANGE BF"),
            OperatorNetwork("orange_bf_v2", "ORANGE BF V2"),
            OperatorNetwork("telecel_bf", "TELECEL BF"),
            OperatorNetwork("global_world", "GLOBAL MONDIAL")
        )
    }
}
