package com.barkatunnel.app.backend

import android.content.Context
import com.barkatunnel.app.device.DeviceIdentity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class BarkaBackendClient(context: Context) {

    companion object {
        const val BASE_URL = "https://api.rhaffservice.shop"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    private val appContext = context.applicationContext
    val deviceId: String = DeviceIdentity.getDeviceId(appContext)

    fun checkAccess(): BackendAccessState {
        val body = post(
            path = "/v1/access/check",
            payload = JSONObject().put("device_id", deviceId)
        )
        return parseAccess(body)
    }

    fun startTrial(): BackendTrialState {
        val body = post(
            path = "/v1/trial/start",
            payload = JSONObject().put("device_id", deviceId)
        )
        return BackendTrialState(
            access = parseAccess(body),
            startedNow = body.optBoolean("started_now", false),
            message = cleanMessage(body.optString("message", "Essai gratuit vérifié."))
        )
    }

    fun isAccessAllowed(): Boolean {
        return try {
            checkAccess().allowed
        } catch (_: Exception) {
            false
        }
    }

    fun startPayment(planId: String): BackendPaymentStart {
        val body = post(
            path = "/v1/payments/start",
            payload = JSONObject()
                .put("device_id", deviceId)
                .put("plan_id", planId)
        )

        val checkoutUrl = body.optString("checkout_url").trim()
        val reference = body.optString("payment_reference").trim()
        if (checkoutUrl.isBlank() || reference.isBlank()) {
            throw BarkaBackendException("Réponse de paiement incomplète.")
        }

        return BackendPaymentStart(
            checkoutUrl = checkoutUrl,
            paymentReference = reference,
            amount = body.optInt("amount", 0),
            currency = body.optString("currency", "XOF")
        )
    }

    fun checkPaymentStatus(paymentReference: String): BackendPaymentStatus {
        val body = post(
            path = "/v1/payments/status",
            payload = JSONObject()
                .put("device_id", deviceId)
                .put("payment_reference", paymentReference)
                .put("sync_provider", true)
        )

        val rawCode = body.optString("activation_code", "").trim()
        return BackendPaymentStatus(
            status = body.optString("status", "error").lowercase(),
            activationCode = rawCode.ifBlank { null },
            message = cleanMessage(body.optString("message", "Statut du paiement vérifié."))
        )
    }

    fun redeemActivationCode(code: String): BackendActivationResult {
        val body = post(
            path = "/v1/activation/redeem",
            payload = JSONObject()
                .put("device_id", deviceId)
                .put("code", code)
        )

        return BackendActivationResult(
            success = body.optBoolean("success", false),
            message = cleanMessage(body.optString("message", "Validation terminée.")),
            access = parseAccess(body.optJSONObject("access") ?: JSONObject())
        )
    }

    private fun parseAccess(body: JSONObject): BackendAccessState {
        return BackendAccessState(
            allowed = body.optBoolean("allowed", false),
            accessType = body.optString("access_type", "NONE"),
            remainingSeconds = body.optLong("remaining_seconds", 0L).coerceAtLeast(0L)
        )
    }

    private fun post(path: String, payload: JSONObject): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()

            val body = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (_: Exception) {
                JSONObject()
            }

            if (status !in 200..299) {
                val detail = body.optString("detail", "Erreur serveur HTTP $status")
                throw BarkaBackendException(cleanMessage(detail))
            }

            return body
        } catch (e: BarkaBackendException) {
            throw e
        } catch (_: Exception) {
            throw BarkaBackendException("Impossible de joindre le serveur Barka Tunnel.")
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanMessage(message: String): String {
        return message.replace(Regex("(?i)lomopay"), "service de paiement")
    }
}

data class BackendAccessState(
    val allowed: Boolean,
    val accessType: String,
    val remainingSeconds: Long
)

data class BackendTrialState(
    val access: BackendAccessState,
    val startedNow: Boolean,
    val message: String
)

data class BackendPaymentStart(
    val checkoutUrl: String,
    val paymentReference: String,
    val amount: Int,
    val currency: String
)

data class BackendPaymentStatus(
    val status: String,
    val activationCode: String?,
    val message: String
)

data class BackendActivationResult(
    val success: Boolean,
    val message: String,
    val access: BackendAccessState
)

class BarkaBackendException(message: String) : RuntimeException(message)
