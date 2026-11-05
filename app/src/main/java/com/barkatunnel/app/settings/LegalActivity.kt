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
import com.google.android.material.card.MaterialCardView
import com.barkatunnel.app.BuildConfig
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
        setContentView(R.layout.activity_legal)
        findViewById<View>(R.id.legalBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.legalPageTitle).apply {
            text = getString(titles[policy] ?: R.string.settings_about)
            accessibilityHeadingCompat()
        }
        fun text(value: String, heading: Boolean = false): TextView = TextView(this).apply {
            this.text = value
            textSize = if (heading) 18f else 16f
            setTextColor(if (heading) BrandTheme.color(context, R.color.barka_blue) else ContextCompat.getColor(context, R.color.barka_text))
            if (heading) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(!heading)
            if (heading) accessibilityHeadingCompat()
        }
        val content = findViewById<LinearLayout>(R.id.legalContent)
        findViewById<ScrollView>(R.id.legalScroll).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        fun card(vararg children: View) {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(12))
                children.forEach { addView(it) }
            }
            content.addView(MaterialCardView(this).apply {
                radius = dp(20).toFloat()
                cardElevation = dp(2).toFloat()
                strokeWidth = dp(1)
                setStrokeColor(ContextCompat.getColor(context, R.color.barka_border))
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.barka_card))
                addView(body)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        card(text(getString(R.string.settings_about_format, BuildConfig.VERSION_NAME), true),
            text("RHAFF SERVICE\nbarkatunnel@gmail.com"),
            text(getString(if (policy == null) R.string.legal_intro else R.string.legal_updated)))
        if (policy == null) {
            val icons = mapOf("cookies" to R.drawable.tile_ic_cookies_barka,
                "privacy" to R.drawable.tile_ic_privacy_barka, "terms" to R.drawable.tile_ic_terms_barka)
            for ((key, label) in titles) content.addView((layoutInflater.inflate(
                R.layout.item_legal_option, content, false) as MaterialButton).apply {
                tag = key
                text = getString(label)
                setIconResource(requireNotNull(icons[key]))
                setOnClickListener { startActivity(Intent(this@LegalActivity, LegalActivity::class.java).putExtra("policy", key)) }
            })
        } else {
            val language = (androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0]?.language ?: "fr").takeIf { it in setOf("fr", "en", "es", "ar") } ?: "fr"
            val document = assets.open("legal/$language.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val sections = document.getJSONArray(policy)
            for (index in 0 until sections.length()) {
                val section = sections.getJSONArray(index)
                card(text("${index + 1}. ${section.getString(0)}", true),
                    text(section.getString(1)))
            }
        }
        SystemBars.apply(this)
        // SystemBars accounts for the legacy screens' header inset; this screen is fully native.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(findViewById<View>(android.R.id.content))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun View.accessibilityHeadingCompat() { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
}
