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
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.vpnc6.BarkaVpnService
import java.net.URI

/** Each section opens the source selected by the administrator. */
object VideoGuide {
    fun bind(activity: Activity, button: View, key: String) {
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            button.isEnabled = false
            Thread {
                val id = runCatching {
                    selectedId(BarkaBackendClient(activity).videoGuides(), key)
                }.getOrNull()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    button.isEnabled = true
                    if (id == null) {
                        Toast.makeText(activity, R.string.video_guide_unavailable, Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    show(activity, id, true)
                }
            }.start()
        }
    }

    internal fun selectedId(config: org.json.JSONObject, key: String): String {
        require(key == "guide" || key == "ip_finder")
        val source = config.optString("${key}_source", "mp4")
        if (source == "mp4") return VideoGuidePage.HOSTED_ID
        require(source == "youtube")
        val uri = URI(config.getString(key))
        require(uri.scheme == "https" && uri.rawAuthority == "www.youtube.com" && uri.path == "/watch")
        val query = requireNotNull(uri.rawQuery)
        require(query.matches(Regex("v=[A-Za-z0-9_-]{11}")))
        return query.removePrefix("v=")
    }

    internal fun show(activity: Activity, id: String, allowHostedFallback: Boolean = false) {
        val player = try {
            WebView(activity)
        } catch (_: RuntimeException) {
            Toast.makeText(activity, R.string.video_guide_webview, Toast.LENGTH_LONG).show()
            return
        }
        player.settings.javaScriptEnabled = true
        player.settings.domStorageEnabled = true
        player.settings.mediaPlaybackRequiresUserGesture = false
        player.settings.allowFileAccess = false
        player.settings.allowContentAccess = false
        player.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        val proxySupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        val uiExecutor = ContextCompat.getMainExecutor(activity)
        var dismissed = false
        var rendererGone = false
        var routeInitialized = false
        var routedPort: Int? = null
        var foreground = true
        var loadPending = false
        var attempt = 0
        var usingHosted = id == VideoGuidePage.HOSTED_ID
        var reloadHosted: (() -> Unit)? = null
        fun useHostedFallback(): Boolean {
            if (!allowHostedFallback || usingHosted || dismissed || rendererGone) return false
            usingHosted = true
            com.barkatunnel.app.journal.AppLogStore.add(activity, "Guide vidéo • lecture MP4 de secours")
            reloadHosted?.invoke()
            return true
        }
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val progress = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.barka_blue))
        }
        val status = android.widget.TextView(activity).apply {
            setText(R.string.video_guide_loading)
            setTextColor(ContextCompat.getColor(activity, R.color.barka_text_secondary))
            textSize = 14f
            setPadding(0, dp(12), 0, dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        var failed = false
        var scheduleBufferingTimeout: (() -> Unit)? = null
        fun showPlaybackError(message: Int = R.string.video_guide_unavailable, diagnostic: String = "network") {
            if (dismissed) return
            failed = true
            progress.visibility = View.INVISIBLE
            status.setText(message)
            // Keep the player and its own useful error visible; never collapse the dialog.
            com.barkatunnel.app.journal.AppLogStore.add(activity, "Guide vidéo • $diagnostic")
        }
        player.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                rendererGone = true
                showPlaybackError(R.string.video_guide_webview, "renderer")
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Never launch arbitrary intents received from embedded content.
                return request.url.scheme != "https" || request.isForMainFrame
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !useHostedFallback()) showPlaybackError(diagnostic = "web ${error.errorCode}")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                if (usingHosted && request.url.toString() in listOf(VideoGuidePage.MEDIA_URL, VideoGuidePage.API_MEDIA_URL)) {
                    com.barkatunnel.app.journal.AppLogStore.add(activity, "Guide vidéo • MP4 HTTP ${response.statusCode} ${request.url.path}")
                }
                if (request.isForMainFrame && !useHostedFallback()) showPlaybackError(diagnostic = "http ${response.statusCode}")
            }
        }
        player.setBackgroundColor(Color.BLACK)
        // YouTube requires the installed app ID as the WebView referrer identity.
        val origin = VideoGuidePage.origin(activity.packageName)
        var playerReady = false
        player.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (dismissed || title == null || !title.startsWith("guide-$attempt-")) return
                val signal = title.removePrefix("guide-$attempt-")
                when (signal) {
                    "ready", "playing" -> {
                        failed = false
                        playerReady = true
                        progress.visibility = View.INVISIBLE
                        status.setText(R.string.video_guide_intro)
                    }
                    "buffering" -> {
                        playerReady = false
                        progress.visibility = View.VISIBLE
                        status.setText(R.string.video_guide_loading)
                        scheduleBufferingTimeout?.invoke()
                    }
                    "network" -> if (!useHostedFallback()) showPlaybackError(diagnostic = "iframe API network")
                    else -> if (signal.startsWith("media-error-")) {
                        showPlaybackError(diagnostic = signal)
                    } else if (signal.startsWith("error-")) {
                        val code = signal.removePrefix("error-").toIntOrNull() ?: return
                        if (useHostedFallback()) return
                        showPlaybackError(when (code) {
                            101, 150, 100 -> R.string.video_guide_restricted
                            153 -> R.string.video_guide_identity
                            else -> R.string.video_guide_unavailable
                        }, "YouTube $code")
                    }
                }
            }
        }
        val timeout = Runnable {
            // Try the supplied MP4 once when YouTube never becomes ready.
            if (!dismissed && !failed && !playerReady && !useHostedFallback()) status.setText(R.string.video_guide_slow)
        }
        scheduleBufferingTimeout = {
            player.removeCallbacks(timeout)
            player.postDelayed(timeout, 30_000)
        }
        fun loadPlayer() {
            if (dismissed || rendererGone) return
            attempt++
            val loadAttempt = attempt
            failed = false
            playerReady = false
            progress.visibility = View.VISIBLE
            status.setText(R.string.video_guide_loading)
            player.removeCallbacks(timeout)
            player.evaluateJavascript("if(typeof pauseGuide==='function')pauseGuide();", null)
            player.stopLoading()
            val load = Runnable {
                if (!dismissed && !rendererGone && attempt == loadAttempt) {
                    if (!foreground) { loadPending = true; return@Runnable }
                    loadPending = false
                    player.loadDataWithBaseURL("$origin/", VideoGuidePage.html(if (usingHosted) VideoGuidePage.HOSTED_ID else id, origin, loadAttempt), "text/html", "UTF-8", null)
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
                } catch (_: RuntimeException) { showPlaybackError(diagnostic = "proxy") }
            } else if (port != null) {
                showPlaybackError(R.string.video_guide_webview, "proxy unsupported")
            } else load.run()
        }
        reloadHosted = { loadPlayer() }
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(progress, android.widget.LinearLayout.LayoutParams(-1, dp(3)))
            addView(status, android.widget.LinearLayout.LayoutParams(-1, -2))
            val metrics = activity.resources.displayMetrics
            val height = ((metrics.widthPixels - 64 * metrics.density) * 9 / 16)
                .coerceAtMost(metrics.heightPixels * 0.45f).coerceAtLeast(200 * metrics.density).toInt()
            addView(player, android.widget.LinearLayout.LayoutParams(-1, height))
        }
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        var lifecycle: androidx.lifecycle.LifecycleEventObserver? = null
        val scroll = android.widget.ScrollView(activity).apply { addView(content) }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.video_guide_title).setView(scroll)
            .setPositiveButton(R.string.video_guide_close, null)
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
        dialog.window?.setLayout(minOf(activity.resources.displayMetrics.widthPixels - dp(24), dp(600)), -2)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .setTextColor(ContextCompat.getColor(activity, R.color.barka_blue))
        dialog.window?.decorView?.let { com.barkatunnel.app.ui.BarkaTypography.apply(it) }
        lifecycle = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> if (!dismissed && !rendererGone) {
                    foreground = false
                    player.evaluateJavascript("if(typeof pauseGuide==='function')pauseGuide();", null)
                    player.onPause()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (!dismissed && !rendererGone) {
                    foreground = true
                    player.onResume()
                    if (loadPending || (routeInitialized && routedPort != BarkaVpnService.connectedSocksPort())) loadPlayer()
                }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> dialog.dismiss()
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(lifecycle)
        loadPlayer()
    }
}
