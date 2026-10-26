package com.barkatunnel.app.guide

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.SystemBars

class GuideSectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide_section)
        SystemBars.apply(this)

        findViewById<View>(R.id.guideSectionBackButton).setOnClickListener { finish() }

        val section = intent.getIntExtra(EXTRA_SECTION, 1).coerceIn(1, 12)
        val content = contentFor(section)

        findViewById<TextView>(R.id.guideSectionHeaderTitle).setText(content.titleRes)
        findViewById<TextView>(R.id.guideSectionNumber).text = getString(R.string.guide_section_number, section)
        val text = getString(content.bodyRes)
        val styled = android.text.SpannableString(text)
        var offset = 0
        for (paragraph in text.split("\n\n")) {
            val end = offset + (paragraph.indexOf('\n').takeIf { it >= 0 } ?: paragraph.length)
            styled.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), offset, end, 0)
            styled.setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(this, R.color.barka_blue)), offset, end, 0)
            offset += paragraph.length + 2
        }
        findViewById<TextView>(R.id.guideSectionBody).text = styled
    }

    private fun contentFor(section: Int): SectionContent = when (section) {
        1 -> SectionContent(R.string.guide_1_title, R.string.guide_1_text, R.drawable.tile_ic_guide_barka)
        2 -> SectionContent(R.string.guide_2_title, R.string.guide_2_text, R.drawable.tile_ic_mobile_barka)
        3 -> SectionContent(R.string.guide_3_title, R.string.guide_3_text, R.drawable.tile_ic_menu_ip_finder)
        4 -> SectionContent(R.string.guide_4_title, R.string.guide_4_text, R.drawable.tile_ic_menu_card_line)
        5 -> SectionContent(R.string.guide_5_title, R.string.guide_5_text, R.drawable.tile_ic_menu_key_line)
        6 -> SectionContent(R.string.guide_6_title, R.string.guide_6_text, R.drawable.tile_ic_guide_connection)
        7 -> SectionContent(R.string.guide_7_title, R.string.guide_7_text, R.drawable.tile_ic_nav_journal)
        9 -> SectionContent(R.string.guide_9_title, R.string.guide_9_text, R.drawable.ic_vpn_share)
        10 -> SectionContent(R.string.guide_10_title, R.string.guide_10_text, R.drawable.ic_vpn_speed)
        11 -> SectionContent(R.string.guide_11_title, R.string.guide_11_text, R.drawable.ic_menu_person_line)
        12 -> SectionContent(R.string.guide_12_title, R.string.guide_12_text, R.drawable.ic_refresh_barka)
        else -> SectionContent(R.string.guide_8_title, R.string.guide_8_text, R.drawable.tile_ic_menu_settings_line)
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
