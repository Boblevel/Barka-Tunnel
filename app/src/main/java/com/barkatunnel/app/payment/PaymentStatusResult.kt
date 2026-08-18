package com.barkatunnel.app.payment

sealed class PaymentStatusResult {

    data object Pending : PaymentStatusResult()

    data class Paid(
        val activationCode: String
    ) : PaymentStatusResult()

    data class Error(
        val message: String
    ) : PaymentStatusResult()
}
