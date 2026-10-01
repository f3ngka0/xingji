package com.tripshare.app.location

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Turns a WGS-84 fix into a short human-readable area label using the platform
 * Geocoder only. Prefers the most specific parts the system can resolve —
 * a district/county plus a street or landmark — and deliberately leaves out
 * the city level. Returns null when the system cannot name the place; callers
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
        val area = address.subLocality?.trim().takeIf { !it.isNullOrBlank() }
        val street = sequenceOf(address.thoroughfare, address.featureName)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
        val cleaned = listOfNotNull(area, street)
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotBlank() && it != "null" }
            .distinctBy { it.lowercase() }
        // A street string that already contains the district adds no detail.
        val parts = if (cleaned.size > 1 && cleaned[1].contains(cleaned[0])) listOf(cleaned[1]) else cleaned
        return when (parts.size) {
            0 -> null
            1 -> "${parts[0]}附近"
            else -> "${parts[0]} · ${parts[1]}附近"
        }
    }
}
