package com.barkatunnel.app.guide

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.vpnc6.BarkaVpnService
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
            openExternal(activity, id)
            return
        }
        player.settings.javaScriptEnabled = true
        player.settings.domStorageEnabled = true
        player.settings.mediaPlaybackRequiresUserGesture = true
        player.settings.allowFileAccess = false
        player.settings.allowContentAccess = false
        player.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        val proxySupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        val uiExecutor = ContextCompat.getMainExecutor(activity)
        var dismissed = false
        var rendererGone = false
        var routeInitialized = false
        var routedPort: Int? = null
        val status = android.widget.TextView(activity).apply {
            setText(R.string.video_guide_intro)
            setPadding(8, 4, 8, 16)
        }
        fun showPlaybackError() {
            status.setText(R.string.video_guide_unavailable)
            player.visibility = View.GONE
        }
        player.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                rendererGone = true
                showPlaybackError()
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Never launch arbitrary intents received from embedded content.
                return request.url.scheme != "https"
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showPlaybackError()
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
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
                title="YouTube" referrerpolicy="strict-origin-when-cross-origin"
                allow="encrypted-media; picture-in-picture" allowfullscreen></iframe>
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
            if (!playerReady) showPlaybackError()
        }
        fun loadPlayer() {
            if (rendererGone) { openExternal(activity, id); return }
            playerReady = false
            player.visibility = View.VISIBLE
            status.setText(R.string.video_guide_intro)
            player.removeCallbacks(timeout)
            val load = Runnable {
                if (!dismissed && !rendererGone) {
                    player.loadDataWithBaseURL("$origin/", html, "text/html", "UTF-8", null)
                    player.postDelayed(timeout, 30_000)
                }
            }
            // Barka itself is excluded from the TUN. Route WebView explicitly,
            // just like the backend client; do not fall back to a direct route.
            val port = BarkaVpnService.connectedSocksPort()
            routedPort = port
            routeInitialized = true
            if (proxySupported) {
                try {
                    if (port != null) ProxyController.getInstance().setProxyOverride(
                        ProxyConfig.Builder().addProxyRule("socks://127.0.0.1:$port").build(), uiExecutor, load)
                    else ProxyController.getInstance().clearProxyOverride(uiExecutor, load)
                } catch (_: RuntimeException) { showPlaybackError() }
            } else if (port != null) {
                showPlaybackError()
                openExternal(activity, id)
            } else load.run()
        }
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            addView(status)
            val metrics = activity.resources.displayMetrics
            val height = ((metrics.widthPixels - 64 * metrics.density) * 9 / 16)
                .coerceAtMost(metrics.heightPixels * 0.45f).coerceAtLeast(120 * metrics.density).toInt()
            addView(player, android.widget.LinearLayout.LayoutParams(-1, height))
        }
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        var lifecycle: androidx.lifecycle.LifecycleEventObserver? = null
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.video_guide_title).setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.download_resume, null)
            .setPositiveButton(R.string.video_guide_external) { _, _ -> openExternal(activity, id) }
            .create()
        dialog.setOnDismissListener {
            dismissed = true
            lifecycle?.let { owner?.lifecycle?.removeObserver(it) }
            player.removeCallbacks(timeout)
            if (!rendererGone) {
                player.stopLoading()
                player.onPause()
                content.removeView(player)
                player.destroy()
            }
            if (proxySupported) runCatching {
                ProxyController.getInstance().clearProxyOverride(uiExecutor) {}
            }
        }
        dialog.show()
        dialog.window?.decorView?.let { com.barkatunnel.app.ui.BarkaTypography.apply(it) }
        lifecycle = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> if (!dismissed && !rendererGone) {
                    player.evaluateJavascript("var p=document.getElementById('player');if(p)p.contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}), 'https://www.youtube.com');", null)
                    player.onPause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (!dismissed && !rendererGone) {
                    player.onResume()
                    if (routeInitialized && routedPort != BarkaVpnService.connectedSocksPort()) loadPlayer()
                }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> dialog.dismiss()
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(lifecycle)
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { loadPlayer() }
        loadPlayer()
    }

    private fun openExternal(activity: Activity, id: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
        } catch (_: RuntimeException) {
            Toast.makeText(activity, R.string.video_guide_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
