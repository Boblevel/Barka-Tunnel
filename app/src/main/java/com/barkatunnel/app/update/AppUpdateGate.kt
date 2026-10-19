package com.barkatunnel.app.update

object AppUpdateGate {
    @Volatile private var update: com.barkatunnel.app.backend.BackendAppUpdate? = null
    fun requiredUpdate() = update
    fun setRequired(value: com.barkatunnel.app.backend.BackendAppUpdate) {
        setRequired(value.apkUrl, value.message)
        update = value
    }
    @Volatile
    private var blocked = false

    @Volatile
    private var apkUrl = ""

    @Volatile
    private var message = ""

    fun setRequired(url: String, text: String) {
        update = null
        apkUrl = url
        message = text
        blocked = true
    }

    fun clear() {
        update = null
        blocked = false
        apkUrl = ""
        message = ""
    }

    fun isBlocked(): Boolean = blocked
    fun requiredUrl(): String = apkUrl
    fun requiredMessage(): String = message
}
