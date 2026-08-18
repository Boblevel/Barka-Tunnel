package com.barkatunnel.app.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

object AssistantModeHelper {

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
    }

    fun isAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }

    fun createRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) != true) {
            return null
        }

        return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }
}
