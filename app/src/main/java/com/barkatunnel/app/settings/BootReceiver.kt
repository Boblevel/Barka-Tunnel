package com.barkatunnel.app.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.barkatunnel.app.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("barka_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_launch", false)) return

        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
