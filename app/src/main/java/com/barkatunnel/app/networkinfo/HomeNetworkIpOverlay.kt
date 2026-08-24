package com.barkatunnel.app.networkinfo

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.barkatunnel.app.R

object HomeNetworkIpOverlay {

    fun attach(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 18, 24, 18)
            setBackgroundResource(R.drawable.bg_network_selected)

            val params = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(28, 140, 28, 0)
            layoutParams = params
        }

        val title = TextView(activity).apply {
            setText(R.string.network_ip_title)
            setTextColor(activity.getColor(R.color.barka_blue))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val value = TextView(activity).apply {
            id = R.id.networkIpValue
            textSize = 17f
            setTextColor(activity.getColor(R.color.barka_text))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val info = NetworkIpProvider.getCurrent(activity)
        value.text = "${info.ip}  •  ${info.transport}"

        card.addView(title)
        card.addView(value)

        decor.addView(card)
    }
}
