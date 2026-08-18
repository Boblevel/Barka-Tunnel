package com.barkatunnel.app.payment

sealed class PaymentStartResult {

    data class Success(
        val checkoutUrl: String,
        val paymentReference: String
    ) : PaymentStartResult()

    data class Error(
        val message: String
    ) : PaymentStartResult()
}
