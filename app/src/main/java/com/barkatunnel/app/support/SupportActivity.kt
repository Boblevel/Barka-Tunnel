package com.barkatunnel.app.support

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.SystemBars
import com.google.android.material.button.MaterialButton

class SupportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)
        SystemBars.apply(this)
        applySystemBars()

        findViewById<MaterialButton>(R.id.contactSupportButton).setOnClickListener {
            it.isEnabled = false
            val button = it
            Thread {
                val result = runCatching { com.barkatunnel.app.backend.BarkaBackendClient(this).supportUrl() }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    button.isEnabled = true
                    val opened = result.mapCatching { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    if (opened.isFailure) Toast.makeText(this, R.string.support_open_failed, Toast.LENGTH_SHORT).show()
                }
            }.start()
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
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val lightIcons = nightMode != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
    }

}
