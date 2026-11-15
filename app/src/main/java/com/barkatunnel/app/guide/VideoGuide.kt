package com.barkatunnel.app.guide

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
        val player = try {
            WebView(activity)
        } catch (_: RuntimeException) {
            Toast.makeText(activity, R.string.video_guide_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        player.settings.javaScriptEnabled = true
        player.settings.domStorageEnabled = true
        player.settings.mediaPlaybackRequiresUserGesture = true
        val status = android.widget.TextView(activity).apply {
            setText(R.string.video_guide_intro)
            setPadding(8, 4, 8, 16)
        }
        fun showPlaybackError() {
            status.setText(R.string.video_guide_unavailable)
            player.visibility = View.GONE
        }
        player.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showPlaybackError()
            }
        }
        player.setBackgroundColor(Color.BLACK)
        val origin = "https://api.rhaffservice.com"
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body,iframe,#player{width:100%;height:100%;margin:0;border:0;background:#000}</style>
            </head><body>
            <iframe id="player" src="https://www.youtube.com/embed/$id?playsinline=1&amp;enablejsapi=1&amp;origin=$origin"
                title="YouTube" allow="encrypted-media; picture-in-picture" allowfullscreen></iframe>
            <script>
                function onYouTubeIframeAPIReady() {
                    new YT.Player('player', { events: {
                        onReady: function () { document.title = 'player-ready'; },
                        onError: function () { document.title = 'player-error'; }
                    } });
                }
            </script>
            <script src="https://www.youtube.com/iframe_api"></script>
            </body></html>
        """.trimIndent()
        var playerReady = false
        player.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                when (title) {
                    "player-ready" -> {
                        playerReady = true
                        status.setText(R.string.video_guide_intro)
                    }
                    "player-error" -> showPlaybackError()
                }
            }
        }
        val timeout = Runnable {
            if (!playerReady) status.setText(R.string.video_guide_unavailable)
        }
        fun loadPlayer() {
            playerReady = false
            player.visibility = View.VISIBLE
            status.setText(R.string.video_guide_intro)
            player.removeCallbacks(timeout)
            player.loadDataWithBaseURL("$origin/", html, "text/html", "UTF-8", null)
            player.postDelayed(timeout, 20_000)
        }
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            addView(status)
            addView(player, android.widget.LinearLayout.LayoutParams(-1, (240 * activity.resources.displayMetrics.density).toInt()))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.video_guide_title).setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.download_resume, null)
            .create()
        dialog.setOnDismissListener {
            player.removeCallbacks(timeout)
            player.stopLoading()
            content.removeView(player)
            player.destroy()
        }
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { loadPlayer() }
        loadPlayer()
    }
}
