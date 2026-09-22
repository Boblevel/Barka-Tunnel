package com.barkatunnel.app.pricing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.work.*
import com.barkatunnel.app.backend.BarkaBackendClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Silent foreground refresh plus Android-scheduled background synchronization. */
object PricingSync : Application.ActivityLifecycleCallbacks {
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Application
    private var visible = 0
    private val tick = object : Runnable {
        override fun run() {
            if (visible <= 0) return
            refresh(app)
            handler.postDelayed(this, 30_000L)
        }
    }
    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(this)
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching {
            application.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        handler.post { if (visible > 0) refresh(app) }
                    }
                })
        }
        val work = PeriodicWorkRequestBuilder<PricingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(application).enqueueUniquePeriodicWork("barka-pricing", ExistingPeriodicWorkPolicy.KEEP, work)
    }
    fun refresh(context: Context) {
        if (!busy.compareAndSet(false, true)) return
        val application = context.applicationContext
        Thread {
            try { PricingStore.save(application, BarkaBackendClient(application).getPricing()) }
            catch (_: Exception) { /* Keep the last valid catalog without a popup. */ }
            finally { busy.set(false) }
        }.start()
    }
    override fun onActivityStarted(activity: Activity) {
        visible++
        if (visible == 1) { handler.removeCallbacks(tick); handler.post(tick) }
    }
    override fun onActivityStopped(activity: Activity) {
        visible = (visible - 1).coerceAtLeast(0)
        if (visible == 0) handler.removeCallbacks(tick)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

class PricingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        PricingStore.save(applicationContext, BarkaBackendClient(applicationContext).getPricing())
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
