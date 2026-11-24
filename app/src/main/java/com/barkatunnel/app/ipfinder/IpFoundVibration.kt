package com.barkatunnel.app.ipfinder

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore

/** The VPN connected effect, twice. Its callbacks are independent of search polling. */
internal class IpFoundVibration(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun request(foreground: Boolean): Boolean {
        cancelPending()
        return runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else context.getSystemService(Vibrator::class.java)
            if (vibrator?.hasVibrator() != true) {
                AppLogStore.add(context, context.getString(R.string.ip_alert_no_vibrator))
                return false
            }
            val attributes = AudioAttributes.Builder()
                .setUsage(if (foreground) AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                    else AudioAttributes.USAGE_NOTIFICATION).build()
            if (Build.VERSION.SDK_INT >= 29) {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                vibrator.vibrate(effect, attributes)
                handler.postDelayed({
                    runCatching { vibrator.vibrate(effect, attributes) }
                        .onFailure { logFailure(it) }
                }, 250L)
            } else {
                val pattern = longArrayOf(0, 70, 180, 70)
                if (Build.VERSION.SDK_INT >= 26)
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
                else vibrator.vibrate(pattern, -1, attributes)
            }
            AppLogStore.add(context, context.getString(R.string.ip_alert_requested))
            true
        }.getOrElse { logFailure(it); false }
    }

    fun cancelPending() { handler.removeCallbacksAndMessages(null) }

    private fun logFailure(error: Throwable) {
        AppLogStore.add(context, context.getString(R.string.ip_alert_failed, error.javaClass.simpleName))
    }
}
