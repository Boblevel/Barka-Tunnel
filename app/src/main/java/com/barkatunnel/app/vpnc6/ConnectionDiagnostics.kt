package com.barkatunnel.app.vpnc6

import java.security.MessageDigest

/** Bounded technical metadata and sanitized native errors for temporary diagnosis. */
internal object ConnectionDiagnostics {
    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }.take(16)

    fun host(value: String): String = if (value.length in 1..253 &&
        value.matches(Regex("[A-Za-z0-9.:-]+"))) value else "[masqué]"

    fun header(name: String, value: String): String {
        val safeName = name.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,40}")) } ?: "[nom masqué]"
        val safeValue = when (name.lowercase(java.util.Locale.ROOT)) {
            "x-connected-to", "host" -> host(value)
            "connection", "proxy-connection" -> if (value in setOf("keep-alive", "close")) value else "[masqué]"
            "user-agent" -> if (value == "FBAV/0.0") value else "[masqué]"
            else -> "[masqué]"
        }
        return "$safeName : $safeValue • longueur=${value.length}"
    }

    fun nativeSummary(output: String): List<String> {
        val text = output.lowercase(java.util.Locale.ROOT)
        val categories = linkedMapOf(
            "x509:" to "certificat TLS rejeté (x509)",
            "tls: handshake failure" to "négociation TLS rejetée",
            "websocket: bad handshake" to "négociation WebSocket rejetée",
            "connection refused" to "connexion TCP refusée",
            "no such host" to "résolution DNS impossible",
            "i/o timeout" to "délai réseau dépassé",
            "context deadline exceeded" to "délai du moteur dépassé",
            "unexpected eof" to "fermeture prématurée (EOF)",
            "failed to read response header" to "réponse amont illisible ou absente"
        )
        val classified = categories.filterKeys { it in text }.values.toList()
        val details = output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(40)
            .map { com.barkatunnel.app.journal.DiagnosticRedactor.clean(it) }.toList()
        return (classified + details).ifEmpty { listOf("aucune erreur native disponible") }
    }
}
