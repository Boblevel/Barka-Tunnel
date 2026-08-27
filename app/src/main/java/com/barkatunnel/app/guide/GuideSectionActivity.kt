package com.barkatunnel.app.guide

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R

class GuideSectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide_section)

        findViewById<View>(R.id.guideSectionBackButton).setOnClickListener { finish() }

        val section = intent.getIntExtra(EXTRA_SECTION, 1).coerceIn(1, 8)
        val content = contentFor(section)

        findViewById<TextView>(R.id.guideSectionHeaderTitle).setText(content.titleRes)
        findViewById<ImageView>(R.id.guideSectionIcon).setImageResource(content.iconRes)
        findViewById<TextView>(R.id.guideSectionBody).setText(content.bodyRes)
    }

    private fun contentFor(section: Int): SectionContent = when (section) {
        1 -> SectionContent(R.string.guide_1_title, R.string.guide_1_text, R.drawable.ic_guide_barka)
        2 -> SectionContent(R.string.guide_2_title, R.string.guide_2_text, R.drawable.ic_mobile_barka)
        3 -> SectionContent(R.string.guide_3_title, R.string.guide_3_text, R.drawable.ic_menu_ip_finder)
        4 -> SectionContent(R.string.guide_4_title, R.string.guide_4_text, R.drawable.ic_menu_card_line)
        5 -> SectionContent(R.string.guide_5_title, R.string.guide_5_text, R.drawable.ic_menu_key_line)
        6 -> SectionContent(R.string.guide_6_title, R.string.guide_6_text, R.drawable.ic_guide_connection)
        7 -> SectionContent(R.string.guide_7_title, R.string.guide_7_text, R.drawable.ic_nav_journal)
        else -> SectionContent(R.string.guide_8_title, R.string.guide_8_text, R.drawable.ic_menu_settings_line)
    }

    private data class SectionContent(
        val titleRes: Int,
        val bodyRes: Int,
        val iconRes: Int
    )

    companion object {
        private const val EXTRA_SECTION = "guide_section"

        fun createIntent(context: Context, section: Int): Intent =
            Intent(context, GuideSectionActivity::class.java)
                .putExtra(EXTRA_SECTION, section)
    }
}
