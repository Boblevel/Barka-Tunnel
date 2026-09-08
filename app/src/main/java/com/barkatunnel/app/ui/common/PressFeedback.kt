package com.barkatunnel.app.ui.common

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import com.barkatunnel.app.R

object PressFeedback : Application.ActivityLifecycleCallbacks {
    private val observers = mutableMapOf<Activity, Pair<View, ViewTreeObserver.OnGlobalLayoutListener>>()

    fun applyToTree(view: View) {
        if (view.isClickable && view.foreground == null && view !is Button && view !is EditText) {
            val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val mask = GradientDrawable().apply {
                shape = if (view.id == R.id.powerButton) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                cornerRadius = 12f * view.resources.displayMetrics.density
                setColor(Color.WHITE)
            }
            view.foreground = RippleDrawable(
                ColorStateList.valueOf(if (dark) 0x2EFFFFFF else 0x24000000), null, mask
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyToTree(view.getChildAt(index))
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val root = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener { applyToTree(root) }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        observers[activity] = root to listener
    }

    override fun onActivityResumed(activity: Activity) = applyToTree(activity.window.decorView)

    override fun onActivityDestroyed(activity: Activity) {
        observers.remove(activity)?.let { (root, listener) ->
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
