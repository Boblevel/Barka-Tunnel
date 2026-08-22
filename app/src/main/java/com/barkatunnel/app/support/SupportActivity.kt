package com.barkatunnel.app.support

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.barkatunnel.app.R
import com.google.android.material.button.MaterialButton

class SupportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)
        applySystemBars()

        findViewById<MaterialButton>(R.id.contactSupportButton).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/message/XUBALKJE5J2CB1")))
            } catch (_: Exception) {
                Toast.makeText(this, "Impossible d’ouvrir le support.", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<android.view.View>(R.id.supportBackButton).setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBars()
    }

    private fun applySystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.barka_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.barka_background)
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
