package com.barkatunnel.app.ui.home

import java.util.Locale

object ConnectionTimeFormatter {

    fun format(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)

        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val seconds = safe % 60L

        return String.format(
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }

    fun formatRemaining(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val months = safe / MONTH_SECONDS
        val days = (safe % MONTH_SECONDS) / DAY_SECONDS
        val hours = (safe % DAY_SECONDS) / HOUR_SECONDS
        val minutes = (safe % HOUR_SECONDS) / MINUTE_SECONDS
        val seconds = safe % MINUTE_SECONDS

        return when {
            months > 0L -> String.format(Locale.US, "%dmo %02dj %02dh", months, days, hours)
            days > 0L -> String.format(Locale.US, "%02dj %02dh %02dmin", days, hours, minutes)
            hours > 0L -> String.format(Locale.US, "%02dh %02dmin %02ds", hours, minutes, seconds)
            minutes > 0L -> String.format(Locale.US, "%02dmin %02ds", minutes, seconds)
            else -> String.format(Locale.US, "%02ds", seconds)
        }
    }

    private const val MINUTE_SECONDS = 60L
    private const val HOUR_SECONDS = 60L * MINUTE_SECONDS
    private const val DAY_SECONDS = 24L * HOUR_SECONDS
    private const val MONTH_SECONDS = 30L * DAY_SECONDS
}
