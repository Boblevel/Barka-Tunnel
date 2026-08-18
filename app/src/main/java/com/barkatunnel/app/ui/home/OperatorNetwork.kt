package com.barkatunnel.app.ui.home

data class OperatorNetwork(
    val id: String,
    val displayName: String
) {
    companion object {
        val ALL = listOf(
            OperatorNetwork("moov_bf", "MOOV-AFRICA BF 🇧🇫"),
            OperatorNetwork("orange_bf", "ORANGE BF 🇧🇫"),
            OperatorNetwork("telecel_bf", "TELECEL BF 🇧🇫")
        )
    }
}
