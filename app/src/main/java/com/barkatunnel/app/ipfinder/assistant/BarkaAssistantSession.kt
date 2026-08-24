package com.barkatunnel.app.ipfinder.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionSession

class BarkaAssistantSession(
    private val appContext: Context
) : VoiceInteractionSession(appContext) {

    private val handler = Handler(Looper.getMainLooper())
    private var cycleStarted = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (
            cycleStarted ||
            args?.getString(BarkaAssistantService.KEY_SESSION_ACTION) !=
            BarkaAssistantService.SESSION_ACTION_REFRESH_CELLULAR_IP
        ) {
            finish()
            return
        }

        cycleStarted = true
        if (!requestAirplaneMode(enabled = true)) {
            completeCycle(false)
            return
        }

        handler.postDelayed({
            if (!requestAirplaneMode(enabled = false)) {
                completeCycle(false)
            } else {
                handler.postDelayed(
                    { completeCycle(true) },
                    CELLULAR_RESTART_DELAY_MS
                )
            }
        }, AIRPLANE_MODE_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun requestAirplaneMode(enabled: Boolean): Boolean =
        runCatching {
            startVoiceActivity(
                Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE).apply {
                    putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, enabled)
                }
            )
            true
        }.getOrDefault(false)

    private fun completeCycle(succeeded: Boolean) {
        appContext.sendBroadcast(
            Intent(BarkaAssistantService.ACTION_CYCLE_COMPLETED)
                .setPackage(appContext.packageName)
                .putExtra(
                    BarkaAssistantService.EXTRA_CYCLE_SUCCEEDED,
                    succeeded
                )
        )
        finish()
    }

    companion object {
        private const val AIRPLANE_MODE_DELAY_MS = 1_800L
        private const val CELLULAR_RESTART_DELAY_MS = 3_500L
    }
}
