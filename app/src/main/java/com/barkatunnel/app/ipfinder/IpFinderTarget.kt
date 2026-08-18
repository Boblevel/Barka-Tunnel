package com.barkatunnel.app.ipfinder

data class IpFinderTarget(
    val mode: IpFinderMode,
    val title: String,
    val successMessage: String
) {
    companion object {

        val ORANGE = IpFinderTarget(
            mode = IpFinderMode.ORANGE_BF,
            title = "ORANGE BF 🇧🇫",
            successMessage = "Bon IP trouvé. Retourne à l’accueil et connecte-toi sur ORANGE BF 🇧🇫."
        )

        val MOOV = IpFinderTarget(
            mode = IpFinderMode.MOOV_AFRICA_BF,
            title = "MOOV-AFRICA BF 🇧🇫",
            successMessage = "IP compatible détectée. Retourne à l’accueil et connecte-toi sur MOOV-AFRICA BF 🇧🇫."
        )
    }
}
