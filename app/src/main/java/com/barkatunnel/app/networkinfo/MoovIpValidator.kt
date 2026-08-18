package com.barkatunnel.app.networkinfo

object MoovIpValidator {

    fun isCompatible(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false

        return first == 10 && second >= 100
    }
}
