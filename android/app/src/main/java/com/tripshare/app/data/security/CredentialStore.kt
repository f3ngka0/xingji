package com.tripshare.app.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "trip-share-secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val installPrefs by lazy { appContext.getSharedPreferences("trip-share-install", Context.MODE_PRIVATE) }

    fun installationId(): String {
        installPrefs.getString("installation_id", null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        installPrefs.edit().putString("installation_id", generated).apply()
        return generated
    }

    fun rotateInstallationId(): String {
        val generated = UUID.randomUUID().toString()
        installPrefs.edit().putString("installation_id", generated).apply()
        return generated
    }

    fun credential(): String? = runCatching { securePrefs.getString("device_credential", null) }.getOrNull()
    fun deviceId(): String? = runCatching { securePrefs.getString("device_id", null) }.getOrNull()

    fun saveDevice(deviceId: String, credential: String) {
        check(credential.isNotBlank()) { "服务器没有返回设备凭证" }
        securePrefs.edit().putString("device_id", deviceId).putString("device_credential", credential).apply()
    }

    fun saveShareUrl(tripId: String, shareUrl: String) {
        require(tripId.isNotBlank() && shareUrl.isNotBlank())
        securePrefs.edit().putString("share_url_$tripId", shareUrl).apply()
    }

    fun shareUrl(tripId: String): String? = runCatching { securePrefs.getString("share_url_$tripId", null) }.getOrNull()
    fun removeShareUrl(tripId: String) { securePrefs.edit().remove("share_url_$tripId").apply() }

    fun clearCredentials() { securePrefs.edit().clear().apply() }
}
