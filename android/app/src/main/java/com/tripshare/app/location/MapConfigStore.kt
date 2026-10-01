package com.tripshare.app.location

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Whether the enhanced (AMap) map mode can actually take effect. */
enum class MapConfigState { READY, NOT_CONFIGURED, INVALID }

/**
 * Pure decision logic for map providers, kept free of Android types so it can
 * be unit tested. Storage always stays WGS-84; the provider only decides which
 * map renders the data and which extra place capabilities are offered.
 */
object MapProviderSettings {
    fun configState(androidKey: String?, webServiceKey: String?): MapConfigState = when {
        androidKey.isNullOrBlank() && webServiceKey.isNullOrBlank() -> MapConfigState.NOT_CONFIGURED
        else -> MapConfigState.READY
    }

    fun resolve(selected: com.tripshare.app.data.MapProvider, state: MapConfigState, builtInAmapAvailable: Boolean): com.tripshare.app.data.MapProvider =
        if (selected == com.tripshare.app.data.MapProvider.AMAP && (state == MapConfigState.READY || builtInAmapAvailable)) {
            com.tripshare.app.data.MapProvider.AMAP
        } else {
            com.tripshare.app.data.MapProvider.OSM
        }
}

/**
 * Holds the user-entered AMap credentials and the chosen map mode. Keys never
 * leave the device's encrypted storage and are never written to logs or git.
 */
class MapConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val modePrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
    }
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun selectedProvider(): com.tripshare.app.data.MapProvider =
        if (modePrefs.getString(KEY_PROVIDER, null) == com.tripshare.app.data.MapProvider.AMAP.wireValue) {
            com.tripshare.app.data.MapProvider.AMAP
        } else {
            com.tripshare.app.data.MapProvider.OSM
        }

    fun saveSelectedProvider(provider: com.tripshare.app.data.MapProvider) {
        modePrefs.edit().putString(KEY_PROVIDER, provider.wireValue).apply()
    }

    fun amapAndroidKey(): String? =
        runCatching { securePrefs.getString(KEY_ANDROID, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun amapWebServiceKey(): String? =
        runCatching { securePrefs.getString(KEY_WEB_SERVICE, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Persists only the provided keys, so either credential can be filled alone. */
    fun saveAmapKeys(androidKey: String?, webServiceKey: String?) {
        val editor = securePrefs.edit()
        var changed = false
        if (!androidKey.isNullOrBlank()) {
            editor.putString(KEY_ANDROID, androidKey.trim())
            changed = true
        }
        if (!webServiceKey.isNullOrBlank()) {
            editor.putString(KEY_WEB_SERVICE, webServiceKey.trim())
            changed = true
        }
        if (changed) {
            check(editor.commit()) { "高德配置保存失败，请重试" }
        }
    }

    fun amapConfigState(): MapConfigState = MapProviderSettings.configState(amapAndroidKey(), amapWebServiceKey())

    /** True when the app was built with bundled AMap keys (legacy install path). */
    fun builtInAmapAvailable(): Boolean =
        com.tripshare.app.BuildConfig.AMAP_ANDROID_KEY.isNotBlank() &&
            com.tripshare.app.BuildConfig.AMAP_ANDROID_KEY != "CHANGE_ME" &&
            com.tripshare.app.BuildConfig.AMAP_WEB_SERVICE_KEY.isNotBlank() &&
            com.tripshare.app.BuildConfig.AMAP_WEB_SERVICE_KEY != "CHANGE_ME"

    /** Provider a new trip will actually be recorded with. */
    fun resolvedProvider(): com.tripshare.app.data.MapProvider =
        MapProviderSettings.resolve(selectedProvider(), amapConfigState(), builtInAmapAvailable())

    /** Whether the AMap location SDK may be used for sampling (keys present). */
    fun amapLocationAvailable(): Boolean =
        !amapAndroidKey().isNullOrBlank() ||
            (com.tripshare.app.BuildConfig.AMAP_ANDROID_KEY.isNotBlank() &&
                com.tripshare.app.BuildConfig.AMAP_ANDROID_KEY != "CHANGE_ME")

    fun amapLocationKey(): String? =
        amapAndroidKey()
            ?: com.tripshare.app.BuildConfig.AMAP_ANDROID_KEY.takeIf { it.isNotBlank() && it != "CHANGE_ME" }

    private companion object {
        const val MODE_PREFS = "trip-share-map"
        const val SECURE_PREFS = "trip-share-map-secure"
        const val KEY_PROVIDER = "map_provider"
        const val KEY_ANDROID = "amap_android_key"
        const val KEY_WEB_SERVICE = "amap_web_service_key"
    }
}
