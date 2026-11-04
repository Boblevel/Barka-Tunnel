package com.barkatunnel.app.moderation

import android.app.Activity
import android.app.Application
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.settings.LegalActivity
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** UI moderation only. VPN access remains enforced by the existing server access check. */
object ModerationGuard : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var current = WeakReference<Activity>(null)
    private var dialog: AlertDialog? = null
    private var lastRefresh = -60_000L
    private val poll = object : Runnable {
        override fun run() {
            current.get()?.let { refresh(it.applicationContext) }
            if (current.get() != null) handler.postDelayed(this, 60_000L)
        }
    }

    fun record(context: Context, blocked: Boolean) {
        context.getSharedPreferences("barka_moderation", Context.MODE_PRIVATE)
            .edit().putBoolean("app_blocked", blocked).apply()
        handler.post { current.get()?.let { display(it) } }
    }

    private fun refresh(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRefresh < 5_000L) return
        if (!busy.compareAndSet(false, true)) return
        lastRefresh = now
        worker.execute {
            try { BarkaBackendClient(context).moderationStatus() }
            catch (_: Exception) { /* Offline: retain the last confirmed moderation state. */ }
            finally { busy.set(false) }
        }
    }

    private fun display(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || activity is LegalActivity) return
        val blocked = activity.getSharedPreferences("barka_moderation", Context.MODE_PRIVATE)
            .getBoolean("app_blocked", false)
        if (!blocked) { dialog?.dismiss(); dialog = null; return }
        if (dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.moderation_title).setMessage(R.string.moderation_message)
            .setCancelable(false)
            .setPositiveButton(R.string.moderation_retry, null)
            .setNeutralButton(R.string.legal_menu, null)
            .setNegativeButton(R.string.moderation_contact, null)
            .create().also { d ->
                d.show()
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { refresh(activity.applicationContext) }
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    activity.startActivity(Intent(activity, LegalActivity::class.java))
                }
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    try { activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:barkatunnel@gmail.com"))) }
                    catch (_: android.content.ActivityNotFoundException) { }
                }
            }
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        display(activity)
        handler.removeCallbacks(poll)
        handler.post(poll)
    }
    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) {
            current.clear(); handler.removeCallbacks(poll)
            dialog?.dismiss(); dialog = null
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
