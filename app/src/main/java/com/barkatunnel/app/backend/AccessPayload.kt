package com.barkatunnel.app.backend

import org.json.JSONObject
import java.time.Instant
import com.barkatunnel.app.ui.home.ConnectionTimeFormatter

/** Only server-confirmed time; never infer a purchased duration from the phone clock. */
internal object AccessPayload {
    fun remaining(body: JSONObject): Long {
        if (body.opt("allowed") !is Boolean)
            throw BarkaBackendException("Réponse de contrôle d’accès invalide.")
        if (!body.getBoolean("allowed")) return 0L
        if (body.optBoolean("unlimited", false)) return ConnectionTimeFormatter.UNLIMITED_SECONDS
        val remaining = body.optLong("remaining_seconds", 0L)
        if (remaining > 0L) return remaining
        val expires = runCatching { Instant.parse(body.getString("expires_at")).epochSecond }.getOrNull()
        val server = if (body.optLong("server_timestamp", -1L) >= 0L) body.getLong("server_timestamp")
            else runCatching { Instant.parse(body.getString("server_time")).epochSecond }.getOrNull()
        if (expires != null && server != null && expires > server) return expires - server
        // A malformed success must not erase a confirmed activation snapshot.
        throw BarkaBackendException("Durée d’accès non confirmée par le serveur. Actualisez votre accès.")
    }
}
