package com.barkatunnel.app.network

data class HttpResponse(
    val code: Int,
    val body: String
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}
