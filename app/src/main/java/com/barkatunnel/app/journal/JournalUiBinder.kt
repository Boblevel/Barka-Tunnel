package com.barkatunnel.app.journal

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.barkatunnel.app.R

class JournalUiBinder(
    private val context: Context,
    private val journalList: LinearLayout
) {

    fun refresh() {
        journalList.removeAllViews()
        val events = compactEvents(
            AppLogStore.getAll(context)
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull(::toJournalEvent)
        ).takeLast(MAX_VISIBLE_EVENTS)

        if (events.isEmpty()) {
            val empty = TextView(context).apply {
                setText(R.string.journal_empty)
                setTextColor(ContextCompat.getColor(context, R.color.barka_text_secondary))
                textSize = 11f
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }
            journalList.addView(empty)
            return
        }

        events.forEach(::addEventRow)
        journalList.post {
            (journalList.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun compactEvents(events: List<JournalEvent>): List<JournalEvent> {
        val compact = mutableListOf<JournalEvent>()
        events.forEach { event ->
            val previous = compact.lastOrNull()
            if (previous?.type == event.type && event.type != EventType.DIAGNOSTIC) {
                compact[compact.lastIndex] = event.copy(
                    networkName = event.networkName ?: previous.networkName
                )
            } else {
                compact += event
            }
        }
        return compact
    }

    private fun toJournalEvent(line: String): JournalEvent? {
        val parsed = LOG_LINE.find(line)
        val clock = parsed?.groupValues?.getOrNull(1).orEmpty()
        val message = parsed?.groupValues?.getOrNull(2)?.trim().orEmpty().ifBlank { line }
        val value = message.lowercase()
        val networkName = NETWORK_NAME.find(message)?.value

        if (value.contains("permission") || value.contains("appuie sur le bouton pour arrêter")) {
            return null
        }

        val type = when {
            value.startsWith("diagnostic vpn") ||
                value.startsWith("diagnostic moov") ||
                value.startsWith("diagnostic orange") ||
                value.startsWith("diagnostic telecel") -> return null
            value.startsWith("ping :") -> EventType.PING
            value.startsWith("synchronisation des profils") -> EventType.PROFILE_SYNCING
            value.startsWith("profils à jour") ||
                value.startsWith("profils mis à jour") ||
                value.startsWith("services de connexion synchronisés") -> EventType.PROFILE_SYNCED
            value.startsWith("vérification des mises à jour") -> EventType.UPDATE_CHECKING
            value.startsWith("barka tunnel est à jour") -> EventType.APP_CURRENT
            value.startsWith("mise à jour disponible") -> EventType.UPDATE_AVAILABLE
            value.contains("déconnexion en cours") || value.contains("disconnecting") ->
                EventType.DISCONNECTING
            value.contains("déconnexion") || value.contains("déconnecté") ->
                EventType.DISCONNECTED
            value.contains("connexion refusée") ||
                value.contains("connexion n’a pas abouti") ||
                value.contains("vérification du réseau nécessaire") ||
                value.contains("out of range") ||
                value.contains("échec / information") ||
                (value.contains("c6") && value.contains("erreur")) ->
                EventType.REFUSED
            value.contains("vpn connecté") ||
                value.contains("connexion sécurisée établie") ||
                (value.contains("c6") && value.contains("connecté")) ->
                EventType.CONNECTED
            value.contains("tentative de connexion automatique") ->
                EventType.RETRY
            value.contains("tentative de connexion") ||
                value.startsWith("connexion en cours") ->
                EventType.CONNECTING
            else -> return null
        }

        return JournalEvent(
            clock = clock,
            type = type,
            networkName = networkName,
            detail = if (
                type == EventType.DIAGNOSTIC ||
                type == EventType.PING ||
                type == EventType.UPDATE_AVAILABLE
            ) message else null
        )
    }

    private fun addEventRow(event: JournalEvent) {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.item_journal_event, journalList, false)
        val presentation = presentationFor(event)

        row.findViewById<TextView>(R.id.eventDot).setTextColor(
            ContextCompat.getColor(context, presentation.colorRes)
        )
        row.findViewById<TextView>(R.id.eventTitle).text = presentation.title
        row.findViewById<TextView>(R.id.eventMessage).text = presentation.message
        row.findViewById<TextView>(R.id.eventTime).text = event.clock
        journalList.addView(row)
    }

    private fun presentationFor(event: JournalEvent): EventPresentation {
        val network = event.networkName?.let { " · $it" }.orEmpty()
        return when (event.type) {
            EventType.CONNECTING -> EventPresentation(
                title = context.getString(R.string.journal_connecting_title),
                message = context.getString(R.string.journal_connecting_message, network),
                colorRes = R.color.barka_orange
            )
            EventType.REFUSED -> EventPresentation(
                title = context.getString(R.string.journal_refused_title),
                message = context.getString(R.string.journal_refused_message, network),
                colorRes = R.color.barka_red
            )
            EventType.RETRY -> EventPresentation(
                title = context.getString(R.string.journal_retry_title),
                message = context.getString(R.string.journal_retry_message, network),
                colorRes = R.color.barka_orange
            )
            EventType.CONNECTED -> EventPresentation(
                title = context.getString(R.string.journal_connected_title),
                message = context.getString(R.string.journal_connected_message, network),
                colorRes = R.color.barka_green
            )
            EventType.DISCONNECTING -> EventPresentation(
                title = context.getString(R.string.journal_disconnecting_title),
                message = context.getString(R.string.journal_disconnecting_message),
                colorRes = R.color.barka_orange
            )
            EventType.DISCONNECTED -> EventPresentation(
                title = context.getString(R.string.journal_disconnected_title),
                message = context.getString(R.string.journal_disconnected_message),
                colorRes = R.color.barka_blue
            )
            EventType.PROFILE_SYNCING -> EventPresentation(
                title = context.getString(R.string.journal_profile_syncing_title),
                message = context.getString(R.string.journal_profile_syncing_message),
                colorRes = R.color.barka_blue
            )
            EventType.PROFILE_SYNCED -> EventPresentation(
                title = context.getString(R.string.journal_profile_synced_title),
                message = context.getString(R.string.journal_profile_synced_message),
                colorRes = R.color.barka_green
            )
            EventType.UPDATE_CHECKING -> EventPresentation(
                title = context.getString(R.string.journal_update_checking_title),
                message = context.getString(R.string.journal_update_checking_message),
                colorRes = R.color.barka_blue
            )
            EventType.APP_CURRENT -> EventPresentation(
                title = context.getString(R.string.journal_app_current_title),
                message = context.getString(R.string.journal_app_current_message),
                colorRes = R.color.barka_green
            )
            EventType.UPDATE_AVAILABLE -> EventPresentation(
                title = context.getString(R.string.journal_update_available_title),
                message = event.detail.orEmpty(),
                colorRes = R.color.barka_orange
            )
            EventType.PING -> {
                val latency = PING_VALUE.find(event.detail.orEmpty())
                    ?.groupValues?.getOrNull(1)?.toLongOrNull()
                val failed = event.detail.orEmpty().contains("échec", ignoreCase = true)
                EventPresentation(
                    title = context.getString(R.string.journal_latency_title),
                    message = if (failed)
                        context.getString(R.string.journal_latency_failed)
                    else
                        context.getString(R.string.journal_latency_message, latency ?: 0L),
                    colorRes = when {
                        failed -> R.color.barka_red
                        latency == null -> R.color.barka_blue
                        latency <= 300L -> R.color.barka_green
                        latency <= 500L -> R.color.barka_orange
                        else -> R.color.barka_red
                    }
                )
            }
            EventType.DIAGNOSTIC -> EventPresentation(
                title = "Diagnostic VPN",
                message = event.detail.orEmpty(),
                colorRes = R.color.barka_blue
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private enum class EventType {
        CONNECTING,
        REFUSED,
        RETRY,
        CONNECTED,
        DISCONNECTING,
        DISCONNECTED,
        PROFILE_SYNCING,
        PROFILE_SYNCED,
        UPDATE_CHECKING,
        APP_CURRENT,
        UPDATE_AVAILABLE,
        PING,
        DIAGNOSTIC
    }

    private data class JournalEvent(
        val clock: String,
        val type: EventType,
        val networkName: String?,
        val detail: String? = null
    )

    private data class EventPresentation(
        val title: String,
        val message: String,
        val colorRes: Int
    )

    companion object {
        private const val MAX_VISIBLE_EVENTS = 80
        private val LOG_LINE = Regex("^\\[([^]]+)]\\s*(.*)$")
        private val PING_VALUE = Regex("Ping\\s*:\\s*(\\d+)\\s*ms", RegexOption.IGNORE_CASE)
        private val NETWORK_NAME = Regex(
            "MOOV-AFRICA BF|ORANGE BF|TELECEL BF",
            RegexOption.IGNORE_CASE
        )
    }
}
