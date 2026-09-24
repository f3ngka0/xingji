package com.tripshare.app.data

import java.time.Instant

data class PlaceMarker(
    val name: String,
    val lat: Double,
    val lon: Double
)

enum class TrackingMode(val wireValue: String) {
    STANDARD("standard"),
    DETAILED("detailed");

    companion object {
        fun fromWire(value: String): TrackingMode = entries.firstOrNull { it.wireValue == value } ?: STANDARD
    }
}

data class TripSettings(
    val sampleIntervalSec: Int = 300,
    val uploadIntervalSec: Int = 300,
    val mode: TrackingMode = TrackingMode.STANDARD,
    val maxShareSeconds: Int = 86_400
)

data class CapturedPosition(
    val id: String,
    val tripId: String,
    val lat: Double,
    val lon: Double,
    val capturedAt: String,
    val accuracyM: Double,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val source: String?,
    val coordinateSystem: String = "WGS84"
)

data class LocalTripSummary(
    val id: String,
    val title: String,
    val origin: PlaceMarker?,
    val destination: PlaceMarker?,
    val status: String,
    val startedAt: String,
    val endedAt: String?,
    val sampleIntervalSec: Int,
    val uploadIntervalSec: Int,
    val mode: TrackingMode,
    val maxShareSeconds: Int,
    val shareUrl: String?,
    val shareExpiresAt: String?,
    val latestPositionAt: String?,
    val shareRevokedAt: String?
) {
    val isActive: Boolean get() = status == "active" && (endedAt == null)
    val maxShareDeadline: Instant? get() = runCatching { Instant.parse(startedAt).plusSeconds(maxShareSeconds.toLong()) }.getOrNull()
    val isShareExpired: Boolean get() = shareExpiresAt?.let { runCatching { !Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false) } ?: false
    val canShare: Boolean get() = shareUrl != null && shareRevokedAt == null && !isShareExpired
}
