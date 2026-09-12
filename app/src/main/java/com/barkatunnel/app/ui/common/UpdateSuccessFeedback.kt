package com.barkatunnel.app.ui.common

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.barkatunnel.app.R
import java.util.concurrent.atomic.AtomicBoolean

/** One successful automatic check per app process, including activity recreation. */
object UpdateSuccessFeedback {
    private val shown = AtomicBoolean(false)

    @Suppress("DEPRECATION")
    fun showOnce(activity: Activity, message: Int) {
        if (activity.isFinishing || activity.isDestroyed || !shown.compareAndSet(false, true)) return
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(226, 232, 240))
            }
        }
        content.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_barka_logo)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        content.addView(TextView(activity).apply {
            setText(message)
            setTextColor(Color.rgb(15, 23, 42))
            textSize = 15f
            maxWidth = (activity.resources.displayMetrics.widthPixels - dp(112)).coerceAtLeast(dp(120))
        })
        Toast(activity.applicationContext).apply {
            duration = Toast.LENGTH_SHORT
            view = content
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(80))
        }.show()
    }
}
