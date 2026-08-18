package com.barkatunnel.app.journal

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton

class JournalActivity : AppCompatActivity() {

    private lateinit var journalContent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)

        journalContent = findViewById(R.id.journalContent)

        findViewById<MaterialButton>(R.id.clearJournalButton).setOnClickListener {
            AppLogStore.clear(this)
            AppLogStore.add(this, "Informations • journal réinitialisé.")
            refreshLogs()
        }

        findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        findViewById<android.view.View>(R.id.navJournal).setOnClickListener {
            refreshLogs()
        }

        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        if (::journalContent.isInitialized) {
            refreshLogs()
        }
    }

    private fun refreshLogs() {
        val logs = AppLogStore.getAll(this)

        journalContent.text = if (logs.isBlank()) {
            "Aucun événement enregistré.\\n\\nLes tentatives de connexion, changements de réseau et résultats IP Finder apparaîtront ici."
        } else {
            logs
        }
    }
}
