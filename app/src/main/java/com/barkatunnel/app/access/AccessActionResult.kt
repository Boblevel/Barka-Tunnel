package com.barkatunnel.app.access

sealed class AccessActionResult {

    data class Allowed(
        val state: AccessState
    ) : AccessActionResult()

    data class Denied(
        val message: String
    ) : AccessActionResult()

    data class Error(
        val message: String
    ) : AccessActionResult()
}
