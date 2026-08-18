package com.barkatunnel.app.payment

import com.barkatunnel.app.account.AccountSessionStore
import com.barkatunnel.app.network.HttpJsonClient
import org.json.JSONObject

class PaymentService(
    private val client: HttpJsonClient,
    private val sessionStore: AccountSessionStore
) {

    fun startPayment(
        plan: PaymentPlan,
        deviceId: String
    ): PaymentStartResult {

        val session = sessionStore.get()
            ?: return PaymentStartResult.Error("Utilisateur non connecté")

        return try {
            val payload = JSONObject()
                .put("planId", plan.id)
                .put("amount", plan.amountXof)
                .put("deviceId", deviceId)

            val response = client.post(
                endpoint = "payments/create",
                json = payload.toString(),
                token = session.token
            )

            if (!response.isSuccessful) {
                return PaymentStartResult.Error(
                    "Impossible de créer le paiement"
                )
            }

            val body = JSONObject(response.body)

            val checkoutUrl = body.optString("checkoutUrl")
            val paymentReference = body.optString("paymentReference")

            if (checkoutUrl.isBlank()) {
                return PaymentStartResult.Error(
                    "Lien de paiement introuvable"
                )
            }

            PaymentStartResult.Success(
                checkoutUrl = checkoutUrl,
                paymentReference = paymentReference
            )

        } catch (e: Exception) {
            PaymentStartResult.Error(
                e.message ?: "Erreur de paiement"
            )
        }
    }
}
