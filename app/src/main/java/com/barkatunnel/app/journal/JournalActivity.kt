package com.barkatunnel.app.journal

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R

class JournalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)

        findViewById<TextView>(R.id.journalContent).text =
            "Barka Tunnel prêt.\nAucune connexion VPN lancée."
    }
}
