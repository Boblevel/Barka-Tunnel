package com.barkatunnel.app.ipfinder.assistant

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import com.barkatunnel.app.ipfinder.IpFinderActivity

class BarkaAssistantSession(
    private val appContext: Context
) : VoiceInteractionSession(appContext) {

    private val handler = Handler(Looper.getMainLooper())
    private var commandStarted = false
    private var commandReported = false
    private var commandFinished = false
    private var requestedState = false
    private var commandIssued = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setUiEnabled(false)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (commandStarted || args?.getString(BarkaAssistantService.KEY_SESSION_ACTION) !=
            BarkaAssistantService.SESSION_ACTION_SET_AIRPLANE_MODE) {
            finish()
            return
        }

        commandStarted = true
        requestedState = args.getBoolean(BarkaAssistantService.KEY_AIRPLANE_MODE_ENABLED)
        commandIssued = requestAirplaneMode(requestedState)
        handler.postDelayed({
            reportCommandIfNeeded()
            finishAndRestoreIpFinder()
        }, COMMAND_SETTLE_DELAY_MS)
    }

    override fun onTaskFinished(intent: Intent, taskId: Int) {
        reportCommandIfNeeded()
        finishAndRestoreIpFinder()
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

    private fun reportCommandIfNeeded() {
        if (!commandStarted || commandReported) return
        commandReported = true
        BarkaAssistantService.reportSessionCommand(requestedState, commandIssued)
    }

    private fun finishAndRestoreIpFinder() {
        if (commandFinished) return
        commandFinished = true
        handler.removeCallbacksAndMessages(null)
        finish()
        restoreIpFinderTask()
    }

    private fun restoreIpFinderTask() {
        val restored = runCatching {
            val activityManager =
                appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appTask = activityManager.appTasks.firstOrNull { task ->
                task.taskInfo.baseIntent.component?.packageName == appContext.packageName
            } ?: activityManager.appTasks.firstOrNull()
            if (appTask == null) {
                false
            } else {
                if (appTask.taskInfo.topActivity?.className != IpFinderActivity::class.java.name) {
                    appTask.startActivity(
                        appContext,
                        ipFinderIntent(flags = 0),
                        null
                    )
                }
                appTask.moveToFront()
                true
            }
        }.getOrDefault(false)

        if (!restored) {
            runCatching {
                appContext.startActivity(
                    ipFinderIntent(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                )
            }
        }
    }

    private fun ipFinderIntent(flags: Int): Intent =
        Intent(appContext, IpFinderActivity::class.java).apply {
            addFlags(
                flags or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }

    companion object {
        private const val COMMAND_SETTLE_DELAY_MS = 450L
    }
}
