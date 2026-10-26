package com.barkatunnel.app.pricing

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.appcompat.app.AppCompatActivity

/** Visible-screen timer only; no network request and no background wake-up. */
class PromotionTicker(private val activity: AppCompatActivity, private val render: () -> Unit) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, if (PricingStore.read(activity).promotions.isEmpty()) 30_000 else 1000)
        }
    }
    init { activity.lifecycle.addObserver(this) }
    override fun onResume(owner: LifecycleOwner) { handler.removeCallbacks(tick); handler.post(tick) }
    override fun onPause(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
    override fun onDestroy(owner: LifecycleOwner) { handler.removeCallbacksAndMessages(null) }
}
