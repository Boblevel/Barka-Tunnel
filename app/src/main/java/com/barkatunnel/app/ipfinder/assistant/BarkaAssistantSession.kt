package com.barkatunnel.app.ipfinder.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionSession

class BarkaAssistantSession(
    appContext: Context
) : VoiceInteractionSession(appContext) {

    private val handler = Handler(Looper.getMainLooper())
    private var commandStarted = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (commandStarted || args?.getString(BarkaAssistantService.KEY_SESSION_ACTION) !=
            BarkaAssistantService.SESSION_ACTION_SET_AIRPLANE_MODE) {
            finish()
            return
        }

        commandStarted = true
        val enabled = args.getBoolean(BarkaAssistantService.KEY_AIRPLANE_MODE_ENABLED)
        val issued = requestAirplaneMode(enabled)
        handler.postDelayed({
            BarkaAssistantService.reportSessionCommand(enabled, issued)
            finish()
        }, COMMAND_SETTLE_DELAY_MS)
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

    companion object {
        private const val COMMAND_SETTLE_DELAY_MS = 450L
    }
}
