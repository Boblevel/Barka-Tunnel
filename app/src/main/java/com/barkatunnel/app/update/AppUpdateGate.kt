package com.barkatunnel.app.update

object AppUpdateGate {
    @Volatile
    private var blocked = false

    @Volatile
    private var apkUrl = ""

    @Volatile
    private var message = ""

    fun setRequired(url: String, text: String) {
        apkUrl = url
        message = text
        blocked = true
    }

    fun clear() {
        blocked = false
        apkUrl = ""
        message = ""
    }

    fun isBlocked(): Boolean = blocked
    fun requiredUrl(): String = apkUrl
    fun requiredMessage(): String = message
}
