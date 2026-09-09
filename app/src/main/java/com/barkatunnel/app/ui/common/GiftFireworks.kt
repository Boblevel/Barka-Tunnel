package com.barkatunnel.app.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Decorative overlay: never intercepts touches or controls the gift request. */
object GiftFireworks {
    fun show(anchor: View): ValueAnimator? {
        if (!anchor.isAttachedToWindow) return null
        val scale = runCatching {
            Settings.Global.getFloat(anchor.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        if (scale <= 0f) return null
        val host = anchor.rootView
        val origin = IntArray(2)
        val hostOrigin = IntArray(2)
        anchor.getLocationOnScreen(origin)
        host.getLocationOnScreen(hostOrigin)
        val density = anchor.resources.displayMetrics.density
        val burst = Burst(
            origin[0] - hostOrigin[0] + anchor.width / 2f,
            origin[1] - hostOrigin[1] + anchor.height / 2f,
            density
        )
        burst.setBounds(0, 0, host.width, host.height)
        host.overlay.add(burst)
        val animator = ValueAnimator.ofFloat(0f, 1f)
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { animator.cancel() }
        }
        animator.duration = 850L
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener {
            burst.progress = it.animatedValue as Float
            burst.invalidateSelf()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                host.overlay.remove(burst)
                host.removeOnAttachStateChangeListener(detachListener)
            }
        })
        host.addOnAttachStateChangeListener(detachListener)
        animator.start()
        return animator
    }

    private class Burst(val x: Float, val y: Float, val density: Float) : Drawable() {
        var progress = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val colors = intArrayOf(0xFF0D63E7.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFF22C55E.toInt())

        override fun draw(canvas: Canvas) {
            for (cluster in 0..2) {
                val t = ((progress - cluster * 0.12f) / (1f - cluster * 0.12f)).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) continue
                val cx = x + (cluster - 1) * 30f * density
                val cy = y + (if (cluster == 1) 22f else 6f) * density
                for (i in 0 until 14) {
                    val angle = i * 2.0 * PI / 14 + cluster * 0.35
                    val distance = (12f + 64f * (1f - (1f - t) * (1f - t))) * density
                    val dx = cos(angle).toFloat()
                    val dy = sin(angle).toFloat()
                    val px = cx + dx * distance
                    val py = cy + dy * distance + 24f * density * t * t
                    paint.color = colors[(i + cluster) % colors.size]
                    paint.alpha = (255f * (1f - t)).toInt()
                    paint.strokeWidth = 2.5f * density
                    canvas.drawLine(px, py, px - dx * 7f * density, py - dy * 7f * density, paint)
                }
            }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
