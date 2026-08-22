package com.barkatunnel.app.guide

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R

class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        findViewById<View>(R.id.guideBackButton).setOnClickListener { finish() }

        bindToggle(R.id.guide1, R.id.guide1Details)
        bindToggle(R.id.guide2, R.id.guide2Details)
        bindToggle(R.id.guide3, R.id.guide3Details)
        bindToggle(R.id.guide4, R.id.guide4Details)
        bindToggle(R.id.guide5, R.id.guide5Details)
        bindToggle(R.id.guide6, R.id.guide6Details)
        bindToggle(R.id.guide7, R.id.guide7Details)
        bindToggle(R.id.guide8, R.id.guide8Details)
    }

    private fun bindToggle(titleId: Int, detailsId: Int) {
        val title = findViewById<TextView>(titleId)
        val details = findViewById<TextView>(detailsId)

        title.setOnClickListener {
            details.visibility =
                if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }
}
