package com.barkatunnel.app.network

import com.barkatunnel.app.access.AccessCheckRequest
import com.barkatunnel.app.access.AccessState
import com.barkatunnel.app.access.AccessType
import com.barkatunnel.app.access.ActivationDuration
import com.barkatunnel.app.access.ActivationRequest
import com.barkatunnel.app.access.ActivationResult
import com.barkatunnel.app.trial.TrialSession
import com.barkatunnel.app.trial.TrialStartRequest
import com.barkatunnel.app.trial.TrialStatus
import org.json.JSONObject

class RealBarkaApi(
    private val client: HttpJsonClient
) : BarkaApi {

    override fun checkAccess(
        request: AccessCheckRequest
    ): ApiResult<AccessState> {
        return try {
            val json = JSONObject()
                .put("accountId", request.accountId)
                .put("deviceId", request.deviceId)

            val response = client.post(
                endpoint = "access/check",
                json = json.toString()
            )

            if (!response.isSuccessful) {
                return ApiResult.Error("Erreur serveur ${response.code}")
            }

            val body = JSONObject(response.body)

            ApiResult.Success(
                AccessState(
                    allowed = body.getBoolean("allowed"),
                    type = AccessType.valueOf(body.getString("type")),
                    serverTime = body.getLong("serverTime"),
                    expiresAt = JsonUtils.nullableLong(body, "expiresAt"),
                    remainingSeconds = body.getLong("remainingSeconds")
                )
            )
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Erreur réseau")
        }
    }

    override fun startTrial(
        request: TrialStartRequest
    ): ApiResult<TrialSession> {
        return try {
            val json = JSONObject()
                .put("accountId", request.accountId)
                .put("deviceId", request.deviceId)

            val response = client.post(
                endpoint = "trial/start",
                json = json.toString()
            )

            if (!response.isSuccessful) {
                return ApiResult.Error("Impossible de démarrer l'essai")
            }

            val body = JSONObject(response.body)

            ApiResult.Success(
                TrialSession(
                    status = TrialStatus.valueOf(body.getString("status")),
                    serverTime = body.getLong("serverTime"),
                    startedAt = JsonUtils.nullableLong(body, "startedAt"),
                    expiresAt = JsonUtils.nullableLong(body, "expiresAt"),
                    remainingSeconds = body.getLong("remainingSeconds")
                )
            )
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Erreur réseau")
        }
    }

    override fun activateCode(
        request: ActivationRequest
    ): ApiResult<ActivationResult> {
        return try {
            val json = JSONObject()
                .put("accountId", request.accountId)
                .put("deviceId", request.deviceId)
                .put("code", request.code)

            val response = client.post(
                endpoint = "activation/code",
                json = json.toString()
            )

            if (!response.isSuccessful) {
                return ApiResult.Error("Code invalide ou expiré")
            }

            val body = JSONObject(response.body)

            val duration = JsonUtils.nullableString(body, "duration")
                ?.let { ActivationDuration.valueOf(it) }

            ApiResult.Success(
                ActivationResult(
                    success = body.getBoolean("success"),
                    message = body.getString("message"),
                    expiresAt = JsonUtils.nullableLong(body, "expiresAt"),
                    duration = duration
                )
            )
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Erreur réseau")
        }
    }
}
