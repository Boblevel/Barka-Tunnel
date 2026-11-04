package com.barkatunnel.app.settings

import android.os.Bundle
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.SystemBars
import com.barkatunnel.app.ui.BrandTheme
import org.json.JSONObject

/** Offline native legal pages: no WebView, scripts, trackers or remote content. */
class LegalActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val policy = intent.getStringExtra("policy")?.takeIf { it in setOf("cookies", "privacy", "terms") }
        val titles = mapOf("cookies" to R.string.legal_cookies, "privacy" to R.string.legal_privacy, "terms" to R.string.legal_terms)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.barka_background))
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        root.addView(MaterialButton(this).apply {
            text = getString(R.string.legal_back)
            setOnClickListener { finish() }
        })
        fun text(value: String, heading: Boolean = false): TextView = TextView(this).apply {
            this.text = value
            textSize = if (heading) 21f else 16f
            setTextColor(if (heading) BrandTheme.color(context, R.color.barka_blue) else ContextCompat.getColor(context, R.color.barka_text))
            if (heading) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(8))
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(!heading)
            if (heading) accessibilityHeadingCompat()
        }
        root.addView(text(getString(titles[policy] ?: R.string.legal_menu), true))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("Barka Tunnel · RHAFF SERVICE\n04/11/2026"))
        if (policy == null) {
            for ((key, label) in titles) content.addView(MaterialButton(this).apply {
                text = getString(label)
                isAllCaps = false
                minHeight = dp(64)
                setOnClickListener { startActivity(Intent(this@LegalActivity, LegalActivity::class.java).putExtra("policy", key)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            content.addView(text("barkatunnel@gmail.com"))
        } else {
            val language = (androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0]?.language ?: "fr").takeIf { it in setOf("fr", "en", "es", "ar") } ?: "fr"
            val document = assets.open("legal/$language.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val sections = document.getJSONArray(policy)
            for (index in 0 until sections.length()) {
                val section = sections.getJSONArray(index)
                content.addView(text(section.getString(0), true))
                content.addView(text(section.getString(1)))
            }
        }
        setContentView(root)
        SystemBars.apply(this)
        // SystemBars accounts for the legacy screens' header inset; this screen is fully native.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun View.accessibilityHeadingCompat() { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
}
