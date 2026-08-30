package com.barkatunnel.app.journal

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MotionEvent
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.ui.SystemBars
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

class JournalActivity : AppCompatActivity() {

    private lateinit var journalList: LinearLayout
    private lateinit var journalUiBinder: JournalUiBinder
    private var journalLogListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTimeMs = 0L
    private var closingJournal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)
        SystemBars.apply(this)

        journalList = findViewById(R.id.journalList)
        journalUiBinder = JournalUiBinder(this, journalList)

        findViewById<android.view.View>(R.id.journalBackButton).setOnClickListener {
            closeJournal()
        }

        findViewById<MaterialButton>(R.id.clearJournalButton).setOnClickListener {
            AppLogStore.clear(this)
            refreshLogs()
        }

        findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            closeJournal()
        }

        findViewById<android.view.View>(R.id.navJournal).setOnClickListener {
            refreshLogs()
        }

        refreshLogs()
    }

    override fun onStart() {
        super.onStart()
        if (journalLogListener == null) {
            journalLogListener = AppLogStore.registerChangeListener(this) {
                runOnUiThread {
                    if (::journalUiBinder.isInitialized) refreshLogs()
                }
            }
        }
    }

    override fun onStop() {
        journalLogListener?.let {
            AppLogStore.unregisterChangeListener(this, it)
        }
        journalLogListener = null
        super.onStop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var closeAfterDispatch = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x
                swipeStartY = event.y
                swipeStartTimeMs = event.eventTime
            }

            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - swipeStartX
                val deltaY = event.y - swipeStartY
                val density = resources.displayMetrics.density
                val distance = abs(deltaX)
                val durationMs = (event.eventTime - swipeStartTimeMs).coerceAtLeast(1L)
                val minimumDistance = SWIPE_MIN_DISTANCE_DP * density
                val quickFlickDistance = SWIPE_QUICK_FLICK_DISTANCE_DP * density
                closeAfterDispatch =
                    deltaX > 0f &&
                        (distance >= minimumDistance ||
                            (distance >= quickFlickDistance && durationMs <= SWIPE_QUICK_FLICK_MAX_MS)) &&
                        abs(deltaX) > abs(deltaY) * SWIPE_DIRECTION_RATIO
            }
        }

        val handled = super.dispatchTouchEvent(event)
        if (closeAfterDispatch && !closingJournal) {
            closeJournal()
        }
        return handled
    }

    override fun onResume() {
        super.onResume()
        if (::journalList.isInitialized) refreshLogs()
    }

    private fun refreshLogs() {
        journalUiBinder.refresh()
    }

    private fun closeJournal() {
        if (closingJournal) return
        closingJournal = true
        finish()
        overridePendingTransition(R.anim.slide_in_left_fast, R.anim.slide_out_right_fast)
    }

    companion object {
        private const val SWIPE_MIN_DISTANCE_DP = 48f
        private const val SWIPE_QUICK_FLICK_DISTANCE_DP = 24f
        private const val SWIPE_QUICK_FLICK_MAX_MS = 260L
        private const val SWIPE_DIRECTION_RATIO = 1.08f
    }
}
