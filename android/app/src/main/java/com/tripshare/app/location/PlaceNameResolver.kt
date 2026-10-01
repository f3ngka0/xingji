package com.tripshare.app.location

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Turns a WGS-84 fix into a short human-readable area label using the platform
 * Geocoder only. Returns null when the system cannot name the place — callers
 * must show a neutral fallback instead of inventing a location name.
 */
object PlaceNameResolver {

    suspend fun describe(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val addresses = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.CHINA).getFromLocation(lat, lon, 1)
        }.getOrElse { return@withContext null }
        addresses?.firstOrNull()?.let(::labelFrom)
    }

    private fun labelFrom(address: android.location.Address): String? {
        val primary = address.locality ?: address.subAdminArea ?: address.adminArea
        val secondary = address.subLocality ?: address.featureName
        val parts = listOfNotNull(primary, secondary)
            .map { it.trim().replace('\n', ' ') }
            .filter { it.isNotBlank() }
            .distinct()
        return when (parts.size) {
            0 -> null
            1 -> "${parts[0]}附近"
            else -> "${parts[0]} · ${parts[1]}附近"
        }
    }
}
