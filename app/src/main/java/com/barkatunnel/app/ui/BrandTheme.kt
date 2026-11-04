package com.barkatunnel.app.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.barkatunnel.app.R
import java.util.WeakHashMap

object BrandTheme : Application.ActivityLifecycleCallbacks {
    private val applied = WeakHashMap<Activity, String>()
    private fun key(context: Context) = context.getSharedPreferences("barka_settings", Context.MODE_PRIVATE)
        .getString("theme", "light") ?: "light"
    fun color(context: Context, resource: Int): Int {
        if (resource != R.color.barka_blue && resource != R.color.barka_blue_light)
            return ContextCompat.getColor(context, resource)
        val light = resource == R.color.barka_blue_light
        return when (key(context)) {
            "orange" -> if (light) 0xFFFFF1E6.toInt() else 0xFFB54708.toInt()
            "red" -> if (light) 0xFFFFF0F0.toInt() else 0xFFC52828.toInt()
            "yellow" -> if (light) 0xFFFFF7CF.toInt() else 0xFF826300.toInt()
            "purple" -> if (light) 0xFFF4EAFF.toInt() else 0xFF7834C5.toInt()
            else -> ContextCompat.getColor(context, resource)
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        val theme = key(activity)
        applied[activity] = theme
        val overlay = when (theme) {
            "orange" -> R.style.BarkaAccentOrange
            "red" -> R.style.BarkaAccentRed
            "yellow" -> R.style.BarkaAccentYellow
            "purple" -> R.style.BarkaAccentPurple
            else -> 0
        }
        if (overlay != 0) activity.theme.applyStyle(overlay, true)
    }
    override fun onActivityResumed(activity: Activity) {
        if (applied[activity] != key(activity)) activity.recreate()
    }
    override fun onActivityDestroyed(activity: Activity) { applied.remove(activity) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
