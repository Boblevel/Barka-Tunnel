package com.barkatunnel.app.journal

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

class JournalActivity : AppCompatActivity() {

    private lateinit var journalList: LinearLayout
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTimeMs = 0L
    private var closingJournal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)

        journalList = findViewById(R.id.journalList)

        findViewById<android.view.View>(R.id.journalBackButton).setOnClickListener {
            closeJournal()
        }

        findViewById<MaterialButton>(R.id.clearJournalButton).setOnClickListener {
            AppLogStore.clear(this)
            refreshLogs()
        }

        findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            closeJournal()
        }

        findViewById<android.view.View>(R.id.navJournal).setOnClickListener {
            refreshLogs()
        }

        refreshLogs()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var closeAfterDispatch = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x
                swipeStartY = event.y
                swipeStartTimeMs = event.eventTime
            }

            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - swipeStartX
                val deltaY = event.y - swipeStartY
                val density = resources.displayMetrics.density
                val distance = abs(deltaX)
                val durationMs = (event.eventTime - swipeStartTimeMs).coerceAtLeast(1L)
                val minimumDistance = SWIPE_MIN_DISTANCE_DP * density
                val quickFlickDistance = SWIPE_QUICK_FLICK_DISTANCE_DP * density
                closeAfterDispatch =
                    deltaX > 0f &&
                        (distance >= minimumDistance ||
                            (distance >= quickFlickDistance && durationMs <= SWIPE_QUICK_FLICK_MAX_MS)) &&
                        abs(deltaX) > abs(deltaY) * SWIPE_DIRECTION_RATIO
            }
        }

        val handled = super.dispatchTouchEvent(event)
        if (closeAfterDispatch && !closingJournal) {
            closeJournal()
        }
        return handled
    }

    override fun onResume() {
        super.onResume()
        if (::journalList.isInitialized) refreshLogs()
    }

    private fun refreshLogs() {
        journalList.removeAllViews()
        val lines = AppLogStore.getAll(this)
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(80)
            .asReversed()

        if (lines.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Aucun événement enregistré."
                setTextColor(getColor(R.color.barka_text_secondary))
                textSize = 11f
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }
            journalList.addView(empty)
            return
        }

        lines.forEach { line -> addEventRow(line) }
    }

    private fun addEventRow(line: String) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_journal_event, journalList, false)

        val time = Regex("^\\[([^]]+)]\\s*(.*)$").find(line)
        val clock = time?.groupValues?.getOrNull(1).orEmpty()
        val rawMessage = time?.groupValues?.getOrNull(2)?.trim().orEmpty().ifBlank { line }
        val message = userFacingMessage(rawMessage)
        val category = categoryFor(message)

        row.findViewById<TextView>(R.id.eventDot).setTextColor(category.color)
        row.findViewById<TextView>(R.id.eventTitle).text = category.title
        row.findViewById<TextView>(R.id.eventMessage).text = message.replace(" • ", " · ")
        row.findViewById<TextView>(R.id.eventTime).text = clock
        journalList.addView(row)
    }

    private fun userFacingMessage(message: String): String {
        val value = message.lowercase()
        val containsTechnicalDetail = TECHNICAL_CONNECTION_TERMS.containsMatchIn(message) ||
            value.contains("out of range")
        if (!containsTechnicalDetail) return message

        return when {
            value.contains("erreur") || value.contains("échec") || value.contains("out of range") ->
                "Connexion en cours • vérification du réseau nécessaire."
            value.contains("profil récupéré") ->
                "Configuration de connexion récupérée."
            value.contains("démarrage") ->
                "Démarrage de la connexion sécurisée."
            value.contains("transport") || value.contains("validé") ->
                "Connexion sécurisée validée."
            value.contains("interface") ->
                "Activation de la connexion sécurisée sur Android."
            value.contains("connecté") ->
                "Connexion sécurisée établie."
            value.contains("déconnexion") || value.contains("déconnecté") ->
                "Barka Tunnel déconnecté proprement."
            else -> "Mise à jour de la connexion sécurisée."
        }
    }

    private fun closeJournal() {
        if (closingJournal) return
        closingJournal = true
        finish()
        overridePendingTransition(R.anim.slide_in_left_fast, R.anim.slide_out_right_fast)
    }

    private fun categoryFor(message: String): EventCategory {
        val value = message.lowercase()
        return when {
            value.contains("échec") || value.contains("refus") || value.contains("impossible") || value.contains("erreur") ->
                EventCategory("Erreur", Color.rgb(220, 38, 38))
            value.contains("connecté") || value.contains("succès") || value.contains("établie") || value.contains("confirmation reçue") ->
                EventCategory("Succès", Color.rgb(22, 163, 74))
            value.contains("tentative") || value.contains("connexion") || value.contains("paiement") ->
                EventCategory("Tentative", Color.rgb(245, 158, 11))
            else -> EventCategory("Informations", getColor(R.color.barka_blue))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class EventCategory(val title: String, val color: Int)

    companion object {
        private const val SWIPE_MIN_DISTANCE_DP = 48f
        private const val SWIPE_QUICK_FLICK_DISTANCE_DP = 24f
        private const val SWIPE_QUICK_FLICK_MAX_MS = 260L
        private const val SWIPE_DIRECTION_RATIO = 1.08f
        private val TECHNICAL_CONNECTION_TERMS = Regex(
            "(?i)\\b(vless|slowdns|udp|c6|tun2socks|xray|dnstt|socks|udpgw|port)\\b"
        )
    }
}
