package com.barkatunnel.app.backend

import android.content.Context
import com.barkatunnel.app.device.DeviceIdentity
import com.barkatunnel.app.trial.TrialUsageStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class BarkaBackendClient(context: Context) {

    companion object {
        const val BASE_URL = "https://api.rhaffservice.shop"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    private val appContext = context.applicationContext
    val deviceId: String = DeviceIdentity.getDeviceId(appContext)

    fun markConnectionAttempt() {
        post(
            path = "/v1/device/connection-attempt",
            payload = JSONObject().put("device_id", deviceId)
        )
    }

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
        if (!body.optBoolean("success", false) || checkoutUrl.isBlank() ||
            reference.isBlank() || reference == "null" ||
            runCatching { URL(checkoutUrl).let { it.protocol != "https" || it.host.isBlank() } }.getOrDefault(true)) {
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

        val rawCode = if (body.isNull("activation_code")) "" else body.optString("activation_code", "").trim()
        return BackendPaymentStatus(
            status = body.optString("status", "error").lowercase(Locale.ROOT),
            activationCode = rawCode.ifBlank { null },
            message = cleanMessage(body.optString("message", "Statut du paiement vérifié."))
        )
    }


    fun getVpnCatalog(): List<BackendVpnProfileMeta> {
        val array = getArray("/v1/vpn/catalog")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parseVpnProfileMeta(item))
            }
        }
    }

    fun getVpnProfile(networkId: String): BackendVpnProfile {
        val body = post(
            path = "/v1/vpn/profile",
            payload = JSONObject()
                .put("device_id", deviceId)
                .put("network_id", networkId)
        )

        val meta = parseVpnProfileMeta(body)
        val config = body.optJSONObject("config") ?: JSONObject()
        if (!meta.enabled || config.length() == 0) {
            throw BarkaBackendException("Configuration VPN indisponible pour ce réseau.")
        }

        return BackendVpnProfile(
            meta = meta,
            configJson = config.toString()
        )
    }

    private fun parseVpnProfileMeta(body: JSONObject): BackendVpnProfileMeta {
        return BackendVpnProfileMeta(
            networkId = body.optString("network_id", "").trim(),
            displayName = body.optString("display_name", "").trim(),
            protocol = body.optString("protocol", "").trim().uppercase(),
            enabled = body.optBoolean("enabled", false),
            maintenance = body.optBoolean("maintenance", false),
            priority = body.optInt("priority", 100),
            version = body.optInt("version", 0),
            updatedAt = body.optString("updated_at", "").trim()
        )
    }

    fun checkAppUpdate(currentVersionCode: Long): BackendAppUpdate {
        val body = getObject(
            path = "/v1/app/update?version_code=${currentVersionCode.coerceAtLeast(1L)}&t=${System.currentTimeMillis()}"
        )
        if (body.opt("enabled") !is Boolean || body.opt("update_available") !is Boolean ||
            body.opt("force_update") !is Boolean || body.optLong("latest_version_code", 0L) < 1L) {
            throw BarkaBackendException("Réponse de mise à jour invalide.")
        }
        val available = body.optBoolean("enabled", false) &&
            body.optLong("latest_version_code", 1L) > currentVersionCode &&
            body.optBoolean("update_available", false)
        return BackendAppUpdate(
            enabled = body.optBoolean("enabled", false),
            updateAvailable = available,
            forceUpdate = available && body.optBoolean("force_update", false),
            latestVersionCode = body.optLong("latest_version_code", 1L),
            latestVersionName = body.optString("latest_version_name", "").trim(),
            apkUrl = body.optString("apk_url", "").trim(),
            message = cleanMessage(
                body.optString(
                    "message",
                    "Une nouvelle version de Barka Tunnel est disponible."
                )
            ),
            updatedAt = body.optString("updated_at", "").trim()
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
        val week = body.optLong("trial_week_start", -1L)
        val serverTime = body.optLong("server_timestamp", -1L)
        if (body.opt("trial_week_used") is Boolean && week >= 0L && serverTime >= week &&
            serverTime - week < 604_800L) {
            TrialUsageStore.record(appContext, body.getBoolean("trial_week_used"), week, serverTime)
        }
        return BackendAccessState(
            allowed = body.optBoolean("allowed", false),
            accessRevision = body.optLong("access_revision", 0L),
            accessType = body.optString("access_type", "NONE"),
            remainingSeconds = body.optLong("remaining_seconds", 0L).coerceAtLeast(0L)
        )
    }

    private fun getObject(path: String): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")

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

    private fun getArray(path: String): org.json.JSONArray {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()

            if (status !in 200..299) {
                val detail = try { JSONObject(text).optString("detail", "Erreur serveur HTTP $status") }
                catch (_: Exception) { "Erreur serveur HTTP $status" }
                throw BarkaBackendException(cleanMessage(detail))
            }

            return try { org.json.JSONArray(text) }
            catch (_: Exception) { throw BarkaBackendException("Réponse VPN invalide.") }
        } catch (e: BarkaBackendException) {
            throw e
        } catch (_: Exception) {
            throw BarkaBackendException("Impossible de joindre le serveur Barka Tunnel.")
        } finally {
            connection.disconnect()
        }
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
        return message.replace(Regex("(?i)saspay"), "service de paiement")
    }
}

data class BackendVpnProfileMeta(
    val networkId: String,
    val displayName: String,
    val protocol: String,
    val enabled: Boolean,
    val maintenance: Boolean,
    val priority: Int,
    val version: Int,
    val updatedAt: String
)

data class BackendVpnProfile(
    val meta: BackendVpnProfileMeta,
    val configJson: String
)

data class BackendAppUpdate(
    val enabled: Boolean,
    val updateAvailable: Boolean,
    val forceUpdate: Boolean,
    val latestVersionCode: Long,
    val latestVersionName: String,
    val apkUrl: String,
    val message: String,
    val updatedAt: String = ""
)

data class BackendAccessState(
    val allowed: Boolean,
    val accessType: String,
    val remainingSeconds: Long,
    val accessRevision: Long = 0L
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
