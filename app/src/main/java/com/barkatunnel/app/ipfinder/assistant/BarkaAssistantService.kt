package com.barkatunnel.app.ipfinder.assistant

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService

class BarkaAssistantService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        activeService = this
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_CYCLE_COMPLETED =
            "com.barkatunnel.app.action.ASSISTANT_AIRPLANE_CYCLE_COMPLETED"
        const val EXTRA_CYCLE_SUCCEEDED = "cycle_succeeded"
        const val KEY_SESSION_ACTION = "barka_session_action"
        const val SESSION_ACTION_REFRESH_CELLULAR_IP = "refresh_cellular_ip"

        @Volatile
        private var activeService: BarkaAssistantService? = null

        fun isSelected(context: Context): Boolean =
            VoiceInteractionService.isActiveService(
                context,
                ComponentName(context, BarkaAssistantService::class.java)
            )

        fun requestAirplaneCycle(context: Context): Boolean {
            if (!isSelected(context)) return false
            val service = activeService ?: return false
            return runCatching {
                service.showSession(
                    Bundle().apply {
                        putString(
                            KEY_SESSION_ACTION,
                            SESSION_ACTION_REFRESH_CELLULAR_IP
                        )
                    },
                    0
                )
                true
            }.getOrDefault(false)
        }
    }
}
