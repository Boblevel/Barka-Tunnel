package com.barkatunnel.app.journal

/** Keep technical errors intact, redact credentials before persistent storage. */
internal object DiagnosticRedactor {
    fun clean(message: String): String {
        if (message.startsWith("Diagnostic VLESS • config VLESS", ignoreCase = true)) {
            return "Diagnostic VLESS • configuration chargée (identifiants masqués)."
        }
        return message
            .replace(Regex("""(?i)[a-z][a-z0-9+.-]*://\S+"""), "[adresse masquée]")
            .replace(Regex("""(?i)\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b"""), "[UUID masqué]")
            .replace(Regex("""(?i)(authorization|cookie|set-cookie|password|passwd|token|secret|uuid|username|pubkey|public[_ -]?key)\s*[:=][^•\r\n]*"""), "$1=[masqué]")
            .replace('\n', ' ').replace('\r', ' ')
    }
}
