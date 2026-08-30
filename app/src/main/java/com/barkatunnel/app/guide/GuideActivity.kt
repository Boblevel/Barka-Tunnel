package com.barkatunnel.app.guide

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.SystemBars

class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        SystemBars.apply(this)

        findViewById<View>(R.id.guideBackButton).setOnClickListener { finish() }

        bindSection(R.id.guide1, 1)
        bindSection(R.id.guide2, 2)
        bindSection(R.id.guide3, 3)
        bindSection(R.id.guide4, 4)
        bindSection(R.id.guide5, 5)
        bindSection(R.id.guide6, 6)
        bindSection(R.id.guide7, 7)
        bindSection(R.id.guide8, 8)
    }

    private fun bindSection(viewId: Int, section: Int) {
        findViewById<View>(viewId).setOnClickListener {
            startActivity(GuideSectionActivity.createIntent(this, section))
        }
    }
}
