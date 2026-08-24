package com.barkatunnel.app.journal

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
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
        ).takeLast(MAX_VISIBLE_EVENTS).asReversed()

        if (events.isEmpty()) {
            val empty = TextView(context).apply {
                text = "Aucune connexion enregistrée."
                setTextColor(ContextCompat.getColor(context, R.color.barka_text_secondary))
                textSize = 11f
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }
            journalList.addView(empty)
            return
        }

        events.forEach(::addEventRow)
    }

    private fun compactEvents(events: List<JournalEvent>): List<JournalEvent> {
        val compact = mutableListOf<JournalEvent>()
        events.forEach { event ->
            val previous = compact.lastOrNull()
            if (previous?.type == event.type) {
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
            value.contains("tentative de connexion") ||
                value.startsWith("connexion en cours") ->
                EventType.CONNECTING
            else -> return null
        }

        return JournalEvent(
            clock = clock,
            type = type,
            networkName = networkName
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
                title = "Connexion en cours",
                message = "Tentative de connexion$network.",
                colorRes = R.color.barka_orange
            )
            EventType.REFUSED -> EventPresentation(
                title = "Connexion refusée",
                message = "La connexion n’a pas abouti$network.",
                colorRes = R.color.barka_red
            )
            EventType.CONNECTED -> EventPresentation(
                title = "Connecté",
                message = "VPN connecté$network.",
                colorRes = R.color.barka_green
            )
            EventType.DISCONNECTED -> EventPresentation(
                title = "Déconnecté",
                message = "VPN déconnecté.",
                colorRes = R.color.barka_blue
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private enum class EventType {
        CONNECTING,
        REFUSED,
        CONNECTED,
        DISCONNECTED
    }

    private data class JournalEvent(
        val clock: String,
        val type: EventType,
        val networkName: String?
    )

    private data class EventPresentation(
        val title: String,
        val message: String,
        val colorRes: Int
    )

    companion object {
        private const val MAX_VISIBLE_EVENTS = 80
        private val LOG_LINE = Regex("^\\[([^]]+)]\\s*(.*)$")
        private val NETWORK_NAME = Regex(
            "MOOV-AFRICA BF|ORANGE BF|TELECEL BF",
            RegexOption.IGNORE_CASE
        )
    }
}
