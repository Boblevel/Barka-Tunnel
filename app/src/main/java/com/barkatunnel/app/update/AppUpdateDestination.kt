package com.barkatunnel.app.update

object AppUpdateDestination {
    const val APKPURE_URL = "https://apkpure.com/p/com.barkatunnel.app"
    fun isValid(value: String): Boolean {
        if (value.isBlank() || value.length > 1000 ||
            value.any { it.isWhitespace() || it.code < 32 || it.code == 127 || it == '\\' }) return false
        return runCatching {
            val uri = java.net.URI(value)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }
}
