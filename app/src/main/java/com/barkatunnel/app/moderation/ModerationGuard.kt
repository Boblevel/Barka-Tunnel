package com.barkatunnel.app.moderation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.barkatunnel.app.backend.BarkaBackendClient
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Close blocked screens and VPN; server restrictions remain authoritative. */
object ModerationGuard : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var current = WeakReference<Activity>(null)
    private val activities = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
    private var lastRefresh = -60_000L
    private val poll = object : Runnable {
        override fun run() {
            current.get()?.let { refresh(it.applicationContext) }
            if (current.get() != null) handler.postDelayed(this, 15_000L)
        }
    }

    fun record(context: Context, blocked: Boolean) {
        context.getSharedPreferences("barka_moderation", Context.MODE_PRIVATE)
            .edit().putBoolean("app_blocked", blocked).apply()
        handler.post {
            if (blocked) {
                context.applicationContext.stopService(Intent(context, com.barkatunnel.app.vpnc6.BarkaVpnService::class.java))
                context.applicationContext.stopService(Intent(context, com.barkatunnel.app.tools.VpnShareService::class.java))
                context.applicationContext.stopService(Intent(context, com.barkatunnel.app.ipfinder.IpSearchService::class.java))
                activities.toList().forEach(::display)
            }
        }
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
        if (activity.isFinishing || activity.isDestroyed) return
        if (!activity.getSharedPreferences("barka_moderation", Context.MODE_PRIVATE)
                .getBoolean("app_blocked", false)) return
        // Hide sensitive screens immediately, including legal and payment screens.
        activity.window.decorView.visibility = android.view.View.INVISIBLE
        activity.finishAndRemoveTask()
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        // Refresh even a cached blocked state so an administrator can unblock it.
        refresh(activity.applicationContext)
        display(activity)
        handler.removeCallbacks(poll)
        if (!activity.isFinishing) handler.postDelayed(poll, 15_000L)
    }
    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) {
            current.clear(); handler.removeCallbacks(poll)
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        activities.add(activity)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
