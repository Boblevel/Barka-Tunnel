package com.barkatunnel.app.ipfinder

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.barkatunnel.app.R
import com.barkatunnel.app.journal.AppLogStore

/** One VPN-button-style click per completed search, without a delayed second pulse. */
internal class IpFoundVibration(private val context: Context) {
    @Suppress("DEPRECATION")
    fun request(foreground: Boolean, feedbackView: View? = null): Boolean {
        // Use the same view feedback as the VPN connection button when the screen is visible.
        // If Android accepts it, do not also request a second vibration from Vibrator.
        if (foreground && feedbackView?.isShown == true && runCatching {
                feedbackView.performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }.getOrDefault(false)) {
            AppLogStore.add(context, context.getString(R.string.ip_alert_requested))
            return true
        }
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
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                vibrator.vibrate(effect, attributes)
            } else {
                if (Build.VERSION.SDK_INT >= 26)
                    vibrator.vibrate(VibrationEffect.createOneShot(80L, 255), attributes)
                else vibrator.vibrate(80L, attributes)
            }
            AppLogStore.add(context, context.getString(R.string.ip_alert_requested))
            true
        }.getOrElse { logFailure(it); false }
    }

    private fun logFailure(error: Throwable) {
        AppLogStore.add(context, context.getString(R.string.ip_alert_failed, error.javaClass.simpleName))
    }
}
