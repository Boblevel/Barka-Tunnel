package com.barkatunnel.app.device

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceIdentity {

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val raw = "${context.packageName}:$androidId"

        return sha256(raw)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }
}
