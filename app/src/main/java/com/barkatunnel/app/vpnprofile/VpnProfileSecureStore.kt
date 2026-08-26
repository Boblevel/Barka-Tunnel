package com.barkatunnel.app.vpnprofile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VpnProfileSecureStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun save(profile: VpnProfile) {
        val payload = JSONObject()
            .put("network_id", profile.networkId)
            .put("display_name", profile.displayName)
            .put("protocol", profile.protocol.name)
            .put("enabled", profile.enabled)
            .put("maintenance", profile.maintenance)
            .put("priority", profile.priority)
            .put("version", profile.version)
            .put("updated_at", profile.updatedAt)
            .put("config_json", profile.configJson)
            .toString()

        preferences.edit()
            .putString(profileKey(profile.networkId), encrypt(payload))
            .apply()
    }

    @Synchronized
    fun load(networkId: String): VpnProfile? {
        val encrypted = preferences.getString(profileKey(networkId), null)
            ?: return null

        return runCatching {
            val payload = JSONObject(decrypt(encrypted))
            val protocol = VpnProfileProtocol.fromServer(
                payload.getString("protocol")
            ) ?: return@runCatching null

            VpnProfile(
                networkId = payload.getString("network_id"),
                displayName = payload.getString("display_name"),
                protocol = protocol,
                enabled = payload.getBoolean("enabled"),
                maintenance = payload.optBoolean("maintenance", false),
                priority = payload.getInt("priority"),
                version = payload.getInt("version"),
                updatedAt = payload.getString("updated_at"),
                configJson = payload.getString("config_json")
            )
        }.getOrElse {
            preferences.edit().remove(profileKey(networkId)).apply()
            null
        }
    }

    fun lastSuccessfulSyncAt(): Long =
        preferences.getLong(KEY_LAST_SUCCESSFUL_SYNC, 0L)

    fun markSuccessfulSync() {
        preferences.edit()
            .putLong(KEY_LAST_SUCCESSFUL_SYNC, System.currentTimeMillis())
            .apply()
    }

    fun cachedVersion(networkId: String): Int = load(networkId)?.version ?: 0

    fun setMaintenance(networkId: String, maintenance: Boolean) {
        preferences.edit().putBoolean(maintenanceKey(networkId), maintenance).apply()
    }

    fun isMaintenance(networkId: String): Boolean =
        preferences.getBoolean(maintenanceKey(networkId), false)

    fun markRequiredVersion(networkId: String, version: Int) {
        preferences.edit().putInt(requiredVersionKey(networkId), version).apply()
    }

    fun clearRequiredVersion(networkId: String) {
        preferences.edit().remove(requiredVersionKey(networkId)).apply()
    }

    fun requiredVersion(networkId: String): Int =
        preferences.getInt(requiredVersionKey(networkId), 0)

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted).joinToString(SEPARATOR) {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    private fun decrypt(value: String): String {
        val parts = value.split(SEPARATOR, limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            return@synchronized it
        }

        KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun profileKey(networkId: String): String =
        "$KEY_PROFILE_PREFIX$networkId"

    private fun maintenanceKey(networkId: String): String =
        "maintenance_$networkId"

    private fun requiredVersionKey(networkId: String): String =
        "required_version_$networkId"

    companion object {
        private const val PREFERENCES_NAME = "barka_vpn_profiles_secure"
        private const val KEY_PROFILE_PREFIX = "profile_"
        private const val KEY_LAST_SUCCESSFUL_SYNC = "last_successful_sync"
        private const val KEY_ALIAS = "barka_vpn_profile_cache_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val SEPARATOR = "."
        private val KEY_LOCK = Any()
    }
}
