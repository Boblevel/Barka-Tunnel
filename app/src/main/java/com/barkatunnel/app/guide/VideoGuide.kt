package com.barkatunnel.app.guide

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import java.net.URI

/** Only the fixed YouTube embed origin is loaded; the admin supplies an ID through the server. */
object VideoGuide {
    fun bind(activity: Activity, button: View, key: String) {
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            button.isEnabled = false
            Thread {
                val id = runCatching {
                    val link = BarkaBackendClient(activity).videoGuides().optString(key)
                    val uri = URI(link)
                    require(uri.scheme == "https" && uri.host == "www.youtube.com" && uri.path == "/watch")
                    val value = uri.rawQuery?.removePrefix("v=") ?: ""
                    require(value.matches(Regex("[A-Za-z0-9_-]{11}")))
                    value
                }.getOrNull()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    button.isEnabled = true
                    if (id == null) {
                        Toast.makeText(activity, R.string.video_guide_unavailable, Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val prefs = activity.getSharedPreferences("video_guide_intro", Activity.MODE_PRIVATE)
                    if (!prefs.getBoolean(key, false)) {
                        AlertDialog.Builder(activity).setTitle(R.string.video_guide_title)
                            .setMessage(R.string.video_guide_intro)
                            .setPositiveButton(R.string.video_guide_watch) { _, _ -> show(activity, id) }
                            .setNegativeButton(android.R.string.cancel, null).show()
                        prefs.edit().putBoolean(key, true).apply()
                    } else show(activity, id)
                }
            }.start()
        }
    }

    private fun show(activity: Activity, id: String) {
        val player = WebView(activity)
        player.settings.javaScriptEnabled = true
        player.settings.mediaPlaybackRequiresUserGesture = true
        player.webChromeClient = WebChromeClient()
        player.webViewClient = WebViewClient()
        player.setBackgroundColor(Color.BLACK)
        val html = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0;background:black"><iframe width="100%" height="240" src="https://www.youtube.com/embed/$id?playsinline=1" title="YouTube" frameborder="0" allow="encrypted-media; picture-in-picture" allowfullscreen></iframe></body></html>"""
        player.loadDataWithBaseURL("https://api.rhaffservice.com/", html, "text/html", "UTF-8", null)
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            addView(android.widget.TextView(activity).apply {
                text = activity.getString(R.string.video_guide_intro)
                setPadding(8, 4, 8, 16)
            })
            addView(player, android.widget.LinearLayout.LayoutParams(-1, (240 * activity.resources.displayMetrics.density).toInt()))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.video_guide_title).setView(content)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener { player.stopLoading(); player.destroy() }
        dialog.show()
    }
}
