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
    private var closingJournal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)

        journalList = findViewById(R.id.journalList)

        findViewById<android.view.View>(R.id.journalBackButton).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.clearJournalButton).setOnClickListener {
            AppLogStore.clear(this)
            refreshLogs()
        }

        findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            finish()
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
            }

            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - swipeStartX
                val deltaY = event.y - swipeStartY
                val minimumDistance = SWIPE_MIN_DISTANCE_DP * resources.displayMetrics.density
                closeAfterDispatch =
                    abs(deltaX) >= minimumDistance &&
                        abs(deltaX) > abs(deltaY) * SWIPE_DIRECTION_RATIO
            }
        }

        val handled = super.dispatchTouchEvent(event)
        if (closeAfterDispatch && !closingJournal) {
            closingJournal = true
            finish()
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
        val message = time?.groupValues?.getOrNull(2)?.trim().orEmpty().ifBlank { line }
        val category = categoryFor(message)

        row.findViewById<TextView>(R.id.eventDot).setTextColor(category.color)
        row.findViewById<TextView>(R.id.eventTitle).text = category.title
        row.findViewById<TextView>(R.id.eventMessage).text = message.replace(" • ", " · ")
        row.findViewById<TextView>(R.id.eventTime).text = clock
        journalList.addView(row)
    }

    private fun categoryFor(message: String): EventCategory {
        val value = message.lowercase()
        return when {
            value.contains("échec") || value.contains("refus") || value.contains("impossible") || value.contains("erreur") ->
                EventCategory("Erreur", Color.rgb(220, 38, 38))
            value.contains("connecté") || value.contains("succès") || value.contains("confirmation reçue") ->
                EventCategory("Succès", Color.rgb(22, 163, 74))
            value.contains("tentative") || value.contains("connexion") || value.contains("paiement") ->
                EventCategory("Tentative", Color.rgb(245, 158, 11))
            else -> EventCategory("Informations", getColor(R.color.barka_blue))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class EventCategory(val title: String, val color: Int)

    companion object {
        private const val SWIPE_MIN_DISTANCE_DP = 96f
        private const val SWIPE_DIRECTION_RATIO = 1.25f
    }
}
