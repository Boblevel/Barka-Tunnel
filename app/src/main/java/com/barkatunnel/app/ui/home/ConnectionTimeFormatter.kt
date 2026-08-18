package com.barkatunnel.app.ui.home

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
}
