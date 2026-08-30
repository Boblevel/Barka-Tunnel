package com.barkatunnel.app.ipfinder.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService

class BarkaAssistantService : VoiceInteractionService() {

    private val handler = Handler(Looper.getMainLooper())
    private var cycleInProgress = false
    private var requestedAirplaneState: Boolean? = null
    private var restoringOnly = false

    override fun onCreate() {
        super.onCreate()
        activeService = this
    }

    override fun onReady() {
        super.onReady()
        activeService = this
    }

    override fun onShutdown() {
        handler.removeCallbacksAndMessages(null)
        cycleInProgress = false
        requestedAirplaneState = null
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cycleInProgress = false
        requestedAirplaneState = null
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun beginAirplaneCycle(): Boolean {
        if (cycleInProgress) return true
        restoringOnly = false
        cycleInProgress = true
        requestAirplaneState(true)
        return true
    }

    private fun cancelCycleAndRestoreNetwork() {
        handler.removeCallbacksAndMessages(null)
        restoringOnly = true
        cycleInProgress = true
        requestedAirplaneState = false
        // Un ordre "ON" peut encore être en transit quand l'utilisateur
        // touche ARRÊTER. On observe brièvement l'état avant de conclure afin
        // de ne jamais laisser le téléphone bloqué en mode avion.
        restoreAirplaneOff(0)
    }

    private fun restoreAirplaneOff(poll: Int) {
        if (!cycleInProgress || !restoringOnly) return
        if (isAirplaneModeEnabled()) {
            requestAirplaneState(false)
            return
        }
        if (poll < RESTORE_GUARD_POLLS) {
            handler.postDelayed(
                { restoreAirplaneOff(poll + 1) },
                STATE_POLL_DELAY_MS
            )
        } else {
            finishCycle(sendResult = false, succeeded = false)
        }
    }

    private fun requestAirplaneState(enabled: Boolean) {
        if (!cycleInProgress) return
        requestedAirplaneState = enabled
        runCatching {
            showSession(
                Bundle().apply {
                    putString(KEY_SESSION_ACTION, SESSION_ACTION_SET_AIRPLANE_MODE)
                    putBoolean(KEY_AIRPLANE_MODE_ENABLED, enabled)
                },
                0
            )
        }.onFailure {
            handler.postDelayed(
                { if (cycleInProgress) requestAirplaneState(enabled) },
                SESSION_RETRY_DELAY_MS
            )
        }
    }

    private fun handleSessionCommand(enabled: Boolean, issued: Boolean) {
        if (!cycleInProgress || requestedAirplaneState != enabled) return
        if (!issued) {
            if (!enabled) {
                handler.postDelayed(
                    { if (cycleInProgress) requestAirplaneState(false) },
                    SESSION_RETRY_DELAY_MS
                )
            } else {
                finishCycle(sendResult = !restoringOnly, succeeded = false)
            }
            return
        }
        waitForAirplaneState(enabled, poll = 0)
    }

    private fun waitForAirplaneState(enabled: Boolean, poll: Int) {
        if (!cycleInProgress || requestedAirplaneState != enabled) return
        if (isAirplaneModeEnabled() == enabled) {
            if (enabled) {
                handler.postDelayed(
                    { if (cycleInProgress) requestAirplaneState(false) },
                    AIRPLANE_HOLD_DELAY_MS
                )
            } else {
                handler.postDelayed(
                    {
                        if (!cycleInProgress) return@postDelayed
                        finishCycle(sendResult = !restoringOnly, succeeded = true)
                    },
                    CELLULAR_RESTART_DELAY_MS
                )
            }
            return
        }

        if (poll < MAX_STATE_POLLS) {
            handler.postDelayed(
                { waitForAirplaneState(enabled, poll + 1) },
                STATE_POLL_DELAY_MS
            )
        } else if (!enabled) {
            // Une fois le mode avion activé, la priorité est toujours de le
            // désactiver. On réessaie jusqu'au retour du réseau ou à l'arrêt
            // demandé par l'utilisateur.
            handler.postDelayed(
                { if (cycleInProgress) requestAirplaneState(false) },
                SESSION_RETRY_DELAY_MS
            )
        } else {
            finishCycle(sendResult = !restoringOnly, succeeded = false)
        }
    }

    private fun isAirplaneModeEnabled(): Boolean =
        Settings.Global.getInt(
            contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) == 1

    private fun finishCycle(sendResult: Boolean, succeeded: Boolean) {
        handler.removeCallbacksAndMessages(null)
        cycleInProgress = false
        requestedAirplaneState = null
        val shouldSend = sendResult
        restoringOnly = false
        if (shouldSend) {
            sendBroadcast(
                android.content.Intent(ACTION_CYCLE_COMPLETED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_CYCLE_SUCCEEDED, succeeded)
            )
        }
    }

    companion object {
        const val ACTION_CYCLE_COMPLETED =
            "com.barkatunnel.app.action.ASSISTANT_AIRPLANE_CYCLE_COMPLETED"
        const val EXTRA_CYCLE_SUCCEEDED = "cycle_succeeded"
        const val KEY_SESSION_ACTION = "barka_session_action"
        const val KEY_AIRPLANE_MODE_ENABLED = "airplane_mode_enabled"
        const val SESSION_ACTION_SET_AIRPLANE_MODE = "set_airplane_mode"

        @Volatile
        private var activeService: BarkaAssistantService? = null

        fun isSelected(context: Context): Boolean {
            val serviceComponent = ComponentName(
                context,
                BarkaAssistantService::class.java
            )
            val activeServiceSelected = runCatching {
                VoiceInteractionService.isActiveService(context, serviceComponent)
            }.getOrDefault(false)
            if (activeServiceSelected) return true

            val secureServiceSelected = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    VOICE_INTERACTION_SERVICE_SETTING
                )
                    ?.substringBefore(':')
                    ?.let { ComponentName.unflattenFromString(it) } == serviceComponent
            }.getOrDefault(false)
            if (secureServiceSelected) return true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleSelected = runCatching {
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    roleManager != null &&
                        roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                        roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                }.getOrDefault(false)
                if (roleSelected) return true
            }

            return false
        }

        fun requestAirplaneCycle(context: Context): Boolean {
            if (!isSelected(context)) return false
            val service = activeService ?: return false
            return runCatching { service.beginAirplaneCycle() }.getOrDefault(false)
        }

        fun cancelAirplaneCycle(context: Context) {
            if (!isSelected(context)) return
            activeService?.cancelCycleAndRestoreNetwork()
        }

        internal fun reportSessionCommand(enabled: Boolean, issued: Boolean) {
            activeService?.handleSessionCommand(enabled, issued)
        }

        private const val AIRPLANE_HOLD_DELAY_MS = 1_500L
        private const val CELLULAR_RESTART_DELAY_MS = 2_500L
        private const val STATE_POLL_DELAY_MS = 250L
        private const val SESSION_RETRY_DELAY_MS = 650L
        private const val MAX_STATE_POLLS = 20
        private const val RESTORE_GUARD_POLLS = 8
        private const val VOICE_INTERACTION_SERVICE_SETTING =
            "voice_interaction_service"
    }
}
