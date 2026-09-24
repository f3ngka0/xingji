package com.tripshare.app.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.tripshare.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class AmapSample(
    val latWgs84: Double,
    val lonWgs84: Double,
    val capturedAt: String,
    val accuracyM: Double,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val source: String?,
    val placeLabel: String?
)

/** One AMap one-shot request per sampling cycle; no persistent high accuracy request is kept alive. */
class AmapLocationSampler(private val context: Context) {
    suspend fun capture(needAddress: Boolean = false, newerThan: String? = null): AmapSample? {
        if (hasAmapSdkKey() && AmapConsentStore.isAccepted(context)) {
            val amapSample = try {
                withTimeout(15_000L) { captureAmap(needAddress) }
            } catch (_: TimeoutCancellationException) {
                null
            } catch (_: Exception) {
                null
            }
            if (amapSample != null && isNewerThan(amapSample.capturedAt, newerThan)) return amapSample
        }
        return AndroidLocationSampler(context).capture(newerThan)
    }

    private fun hasAmapSdkKey() = BuildConfig.AMAP_ANDROID_KEY.isNotBlank() && BuildConfig.AMAP_ANDROID_KEY != "CHANGE_ME"

    private suspend fun captureAmap(needAddress: Boolean): AmapSample? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            var client: AMapLocationClient? = null
            var completed = false
            fun finish(sample: AmapSample?) {
                if (completed) return
                completed = true
                runCatching { client?.stopLocation() }
                runCatching { client?.onDestroy() }
                client = null
                if (continuation.isActive) continuation.resume(sample)
            }

            try {
                // Consent is recorded by the app only after showing the location/privacy disclosure.
                AMapLocationClient.updatePrivacyShow(context.applicationContext, true, true)
                AMapLocationClient.updatePrivacyAgree(context.applicationContext, true)
                val locationClient = AMapLocationClient(context.applicationContext)
                client = locationClient
                val option = AMapLocationClientOption()
                option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                option.setOnceLocation(true)
                option.setOnceLocationLatest(true)
                option.setLocationCacheEnable(true)
                option.setNeedAddress(needAddress)
                option.setHttpTimeOut(30_000)
                locationClient.setLocationOption(option)
                locationClient.setLocationListener { raw ->
                    val sample = validate(raw)
                    if (Looper.myLooper() == Looper.getMainLooper()) finish(sample)
                    else Handler(Looper.getMainLooper()).post { finish(sample) }
                }
                continuation.invokeOnCancellation {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        runCatching { client?.stopLocation() }
                        runCatching { client?.onDestroy() }
                    } else {
                        withContextMainCleanup { client }
                    }
                }
                locationClient.startLocation()
                // The SDK normally returns in under 30 seconds. Cancellation is handled by the caller's timeout.
            } catch (_: Exception) {
                finish(null)
            }
        }
    }

    private fun validate(location: AMapLocation?): AmapSample? {
        if (location == null || location.errorCode != 0) return null
        val lat = location.latitude
        val lon = location.longitude
        val accuracy = location.accuracy.toDouble()
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (!accuracy.isFinite() || accuracy <= 0.0 || accuracy > MAX_ACCEPTABLE_ACCURACY_M) return null
        val capturedMillis = location.time.takeIf { it > 0 } ?: return null
        if (!isFresh(location, capturedMillis)) return null
        val wgs84 = CoordinateTransform.gcj02ToWgs84(lat, lon)
        val speed = if (location.hasSpeed()) location.speed.toDouble().takeIf { it.isFinite() && it >= 0.0 } else null
        val speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond.toDouble().takeIf { it.isFinite() && it >= 0.0 }
        } else null
        val placeName = sequenceOf(location.poiName, location.aoiName, location.city, location.district)
            .firstOrNull { !it.isNullOrBlank() }
        return AmapSample(
            wgs84.lat, wgs84.lon, Instant.ofEpochMilli(capturedMillis).toString(), accuracy, speed, speedAccuracy,
            "amap:${location.locationType}", placeName
        )
    }

    private fun withContextMainCleanup(clientRef: () -> AMapLocationClient?) {
        android.os.Handler(Looper.getMainLooper()).post {
            runCatching { clientRef()?.stopLocation() }
            runCatching { clientRef()?.onDestroy() }
        }
    }

    companion object {
        const val MAX_CACHE_AGE_MS = 60_000L
        const val MAX_FUTURE_SKEW_MS = 60_000L
        const val MAX_ACCEPTABLE_ACCURACY_M = 1_200.0

        internal fun isNewerThan(capturedAt: String, previousCapturedAt: String?): Boolean {
            val currentMillis = runCatching { Instant.parse(capturedAt).toEpochMilli() }.getOrNull() ?: return false
            val previousMillis = previousCapturedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            return previousMillis == null || currentMillis > previousMillis
        }

        internal fun isFresh(location: Location, capturedMillis: Long): Boolean {
            val monoAgeNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            val ageMillis = if (location.elapsedRealtimeNanos > 0 && monoAgeNanos >= 0) monoAgeNanos / 1_000_000L
                else System.currentTimeMillis() - capturedMillis
            val wallSkew = System.currentTimeMillis() - capturedMillis
            return ageMillis <= MAX_CACHE_AGE_MS && ageMillis >= 0L && wallSkew >= -MAX_FUTURE_SKEW_MS
        }
    }
}

/** Android LocationManager fallback returns WGS-84 positions directly and never passes through GCJ-02 conversion. */
private class AndroidLocationSampler(private val context: Context) {
    suspend fun capture(newerThan: String?): AmapSample? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { provider ->
                runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            }
            if (providers.isEmpty()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val cached = providers.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { location ->
                    validate(location, cached = true)
                }
            }.filter { AmapLocationSampler.isNewerThan(it.capturedAt, newerThan) }.minByOrNull { it.accuracyM }
            if (cached != null) {
                continuation.resume(cached)
                return@suspendCancellableCoroutine
            }

            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            lateinit var listener: LocationListener
            fun cleanup() {
                runCatching { manager.removeUpdates(listener) }
                handler.removeCallbacksAndMessages(null)
            }
            fun finish(value: AmapSample?) {
                if (!finished.compareAndSet(false, true)) return
                cleanup()
                if (continuation.isActive) continuation.resume(value)
            }
            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    validate(location, cached = false)
                        ?.takeIf { AmapLocationSampler.isNewerThan(it.capturedAt, newerThan) }
                        ?.let(::finish)
                }
                @Deprecated("Deprecated by Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            try {
                providers.forEach { provider ->
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
                handler.postDelayed({ finish(null) }, NATIVE_REQUEST_TIMEOUT_MS)
            } catch (_: SecurityException) {
                finish(null)
            } catch (_: IllegalArgumentException) {
                finish(null)
            }
            continuation.invokeOnCancellation { handler.post { cleanup() } }
        }
    }

    private fun validate(location: Location, cached: Boolean): AmapSample? {
        val lat = location.latitude
        val lon = location.longitude
        val accuracy = location.accuracy.toDouble()
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (!accuracy.isFinite() || accuracy <= 0.0 || accuracy > AmapLocationSampler.MAX_ACCEPTABLE_ACCURACY_M) return null
        val capturedMillis = location.time.takeIf { it > 0 } ?: return null
        if (!AmapLocationSampler.isFresh(location, capturedMillis)) return null
        val speed = if (location.hasSpeed()) location.speed.toDouble().takeIf { it.isFinite() && it >= 0.0 } else null
        val speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond.toDouble().takeIf { it.isFinite() && it >= 0.0 }
        } else null
        val provider = location.provider?.lowercase() ?: "unknown"
        return AmapSample(
            lat, lon, Instant.ofEpochMilli(capturedMillis).toString(), accuracy, speed, speedAccuracy,
            "android_location_manager:$provider${if (cached) ":cached" else ""}", null
        )
    }

    companion object { private const val NATIVE_REQUEST_TIMEOUT_MS = 30_000L }
}
