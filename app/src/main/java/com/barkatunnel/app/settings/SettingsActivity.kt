package com.barkatunnel.app.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialButton>(R.id.supportButton).setOnClickListener {
            val uri = Uri.parse("https://wa.me/message/XUBALKJE5J2CB1")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}
