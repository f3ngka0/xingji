package com.tripshare.app.data

import android.content.Context
import com.tripshare.app.data.local.PositionEntity
import com.tripshare.app.data.local.TripDatabase
import com.tripshare.app.data.local.TripEntity
import com.tripshare.app.data.remote.CreateTripRequest
import com.tripshare.app.data.remote.DeviceRegistrationRequest
import com.tripshare.app.data.remote.PositionInputDto
import com.tripshare.app.data.remote.TripApiFactory
import com.tripshare.app.data.remote.TripDto
import com.tripshare.app.data.remote.ServerAddressValidator
import com.tripshare.app.data.remote.UploadPositionsRequest
import com.tripshare.app.data.remote.toDto
import com.tripshare.app.data.remote.toModel
import com.tripshare.app.data.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TripRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = TripDatabase.get(appContext).tripDao()
    val credentials = CredentialStore(appContext)
    val apiFactory = TripApiFactory(appContext, credentials)
    private val serverOperationMutex = Mutex()
    private val registrationMutex = Mutex()

    fun serverAddress(): String? = apiFactory.currentServerAddress()

    suspend fun configureServerAddress(address: String): String = serverOperationMutex.withLock {
        val normalized = ServerAddressValidator.normalize(address)
        val previous = apiFactory.currentServerAddress()
        val changingServer = previous != normalized
        if (changingServer && dao.localRecordCount() > 0) {
            throw IllegalStateException(
                "这台设备已有本地行程记录或正在记录。为避免把设备凭证或旧行程发送到另一台服务器，暂时不能切换；历史数据会保留。"
            )
        }

        apiFactory.verifyServerAddress(normalized)
        if (changingServer) {
            check(credentials.clearCredentials()) { "无法安全清除旧服务器凭证，服务器地址未更改" }
            credentials.rotateInstallationId()
        }
        apiFactory.saveServerAddress(normalized)
        normalized
    }

    fun observeTrips(): Flow<List<LocalTripSummary>> = dao.observeTrips().map { rows -> rows.map { it.toSummary() } }
    fun observePoints(tripId: String): Flow<List<PositionEntity>> = dao.observePoints(tripId)
    fun observePendingCount(tripId: String): Flow<Int> = dao.observePendingCount(tripId)
    fun observeRejectedCount(tripId: String): Flow<Int> = dao.observeRejectedCount(tripId)
    suspend fun localTrip(id: String): LocalTripSummary? = dao.trip(id)?.toSummary()
    suspend fun activeTrip(): LocalTripSummary? = dao.activeTrip()?.toSummary()
    suspend fun allLocalTrips(): List<LocalTripSummary> = dao.allTrips().map { it.toSummary() }

    suspend fun createTrip(origin: PlaceMarker?, destination: PlaceMarker?, settings: TripSettings): LocalTripSummary = serverOperationMutex.withLock {
        require(settings.sampleIntervalSec in MIN_INTERVAL..MAX_INTERVAL) { "位置间隔需在 1 分钟到 1 小时之间" }
        require(settings.uploadIntervalSec in MIN_INTERVAL..MAX_INTERVAL) { "上传间隔需在 1 分钟到 1 小时之间" }
        require(settings.maxShareSeconds in MIN_SHARE_SECONDS..MAX_SHARE_SECONDS) { "共享时长设置无效" }
        val deviceApi = authenticatedApi()
        val sampleSeconds = if (settings.mode == TrackingMode.DETAILED) 60 else settings.sampleIntervalSec
        val response = deviceApi.createTrip(
            CreateTripRequest(
                origin = origin?.toDto(),
                destination = destination?.toDto(),
                sampleIntervalSec = sampleSeconds,
                uploadIntervalSec = settings.uploadIntervalSec,
                mode = settings.mode.wireValue,
                maxShareSeconds = settings.maxShareSeconds
            )
        )
        val shareUrl = response.shareUrl ?: response.trip.shareUrl
        if (!shareUrl.isNullOrBlank() && apiFactory.isSafeShareUrl(shareUrl)) {
            credentials.saveShareUrl(response.trip.id, shareUrl)
        }
        val entity = response.trip.toEntity()
        dao.putTrip(entity)
        entity.toSummary()
    }

    suspend fun saveObservedPosition(position: CapturedPosition) {
        require(position.coordinateSystem == "WGS84")
        require(position.lat.isFinite() && position.lon.isFinite())
        require(position.lat in -90.0..90.0 && position.lon in -180.0..180.0)
        require(position.accuracyM.isFinite() && position.accuracyM >= 0)
        dao.insertPoint(
            PositionEntity(
                id = position.id,
                tripId = position.tripId,
                lat = position.lat,
                lon = position.lon,
                capturedAt = position.capturedAt,
                accuracyM = position.accuracyM,
                speedMps = position.speedMps,
                speedAccuracyMps = position.speedAccuracyMps,
                source = position.source,
                coordinateSystem = "WGS84"
            )
        )
    }

    suspend fun uploadPending(tripId: String, maxBatches: Int = 20): Int {
        if (maxBatches <= 0) return 0
        return serverOperationMutex.withLock {
            val authenticated = authenticatedApi()
            var synced = 0
            repeat(maxBatches) {
                val batch = dao.pendingPoints(tripId, 100)
                if (batch.isEmpty()) return@withLock synced
                val response = authenticated.uploadPositions(
                    tripId,
                    UploadPositionsRequest(batch.map { row ->
                        PositionInputDto(
                            id = row.id,
                            lat = row.lat,
                            lon = row.lon,
                            capturedAt = row.capturedAt,
                            accuracyM = row.accuracyM,
                            speedMps = row.speedMps,
                            speedAccuracyMps = row.speedAccuracyMps,
                            source = row.source,
                            coordinateSystem = "WGS84"
                        )
                    })
                )
                val accepted = (response.acceptedIds + response.duplicateIds).distinct()
                if (accepted.isNotEmpty()) {
                    dao.markSynced(tripId, accepted)
                    synced += accepted.size
                }
                response.rejected.forEach { dao.markRejected(tripId, it.id, it.code.take(120)) }
                if (accepted.isEmpty() && response.rejected.isEmpty()) return@withLock synced
                if (batch.all { row -> row.id !in accepted && response.rejected.any { it.id == row.id } }) return@withLock synced
            }
            synced
        }
    }

    suspend fun updateSettings(tripId: String, settings: TripSettings): LocalTripSummary = serverOperationMutex.withLock {
        require(settings.sampleIntervalSec in MIN_INTERVAL..MAX_INTERVAL)
        require(settings.uploadIntervalSec in MIN_INTERVAL..MAX_INTERVAL)
        require(settings.maxShareSeconds in MIN_SHARE_SECONDS..MAX_SHARE_SECONDS)
        val sampleSeconds = if (settings.mode == TrackingMode.DETAILED) 60 else settings.sampleIntervalSec
        val updated = authenticatedApi().updateSettings(
            tripId,
            com.tripshare.app.data.remote.SettingsRequest(
                sampleSeconds, settings.uploadIntervalSec, settings.mode.wireValue, settings.maxShareSeconds
            )
        ).trip
        cacheShareUrl(updated.id, updated.shareUrl, updated.shareExpiresAt, updated.shareRevokedAt)
        val entity = updated.toEntity()
        dao.putTrip(entity)
        entity.toSummary()
    }

    suspend fun endTrip(tripId: String, reason: String = "manual"): Boolean = serverOperationMutex.withLock {
        val now = Instant.now().toString()
        dao.setEnded(tripId, "ended", now, reason, "pending")
        try {
            val remote = authenticatedApi().endTrip(tripId).trip
            cacheShareUrl(remote.id, remote.shareUrl, remote.shareExpiresAt, remote.shareRevokedAt)
            val updated = remote.toEntity()
            dao.putTrip(updated)
            dao.markEndSynced(tripId)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun syncEnd(tripId: String) = serverOperationMutex.withLock {
        val trip = dao.trip(tripId) ?: return@withLock
        if (trip.status == "ended" && trip.endSyncState == "pending") {
            authenticatedApi().endTrip(tripId)
            dao.markEndSynced(tripId)
        }
    }

    suspend fun pendingCount(tripId: String) = dao.pendingCount(tripId)

    suspend fun revokeShare(tripId: String) = serverOperationMutex.withLock {
        val remote = authenticatedApi().revokeShare(tripId).trip
        credentials.removeShareUrl(tripId)
        dao.putTrip(remote.toEntity().copy(shareRevokedAt = remote.shareRevokedAt ?: Instant.now().toString()))
    }

    suspend fun refreshTrips(): List<LocalTripSummary> = serverOperationMutex.withLock {
        val remote = authenticatedApi().listTrips().trips
        remote.forEach { dto ->
            val existing = dao.trip(dto.id)
            cacheShareUrl(dto.id, dto.shareUrl, dto.shareExpiresAt, dto.shareRevokedAt)
            dao.putTrip(preservePendingLocalEnd(existing, dto.toEntity()))
        }
        dao.allTrips().map { it.toSummary() }
    }

    suspend fun refreshTrip(tripId: String): LocalTripSummary = serverOperationMutex.withLock {
        val remote = authenticatedApi().getTrip(tripId).trip
        val existing = dao.trip(tripId)
        cacheShareUrl(tripId, remote.shareUrl, remote.shareExpiresAt, remote.shareRevokedAt)
        val entity = preservePendingLocalEnd(existing, remote.toEntity())
        dao.putTrip(entity)
        entity.toSummary()
    }

    suspend fun deleteTrip(tripId: String) = serverOperationMutex.withLock {
        authenticatedApi().deleteTrip(tripId)
        credentials.removeShareUrl(tripId)
        dao.deleteTrip(tripId)
    }

    suspend fun pointCount(tripId: String): Int = dao.allPoints(tripId).size
    suspend fun latestPoint(tripId: String): PositionEntity? = dao.latestPoint(tripId)

    private suspend fun authenticatedApi() = registrationMutex.withLock {
        apiFactory.assertServerConfigured()
        val api = apiFactory.create()
        if (credentials.credential().isNullOrBlank()) {
            var registration = api.registerDevice(DeviceRegistrationRequest(credentials.installationId()))
            if (registration.credential.isNullOrBlank()) {
                // The secure preference may have been removed while the server still knows this installation ID.
                registration = api.registerDevice(DeviceRegistrationRequest(credentials.rotateInstallationId()))
            }
            val secret = registration.credential ?: throw IllegalStateException("服务器未能签发设备凭证，请重新安装后重试")
            credentials.saveDevice(registration.deviceId, secret)
        }
        apiFactory.create()
    }

    private fun TripDto.toEntity() = TripEntity(
        id = id,
        title = title,
        originName = origin?.name,
        originLat = origin?.lat,
        originLon = origin?.lon,
        destinationName = destination?.name,
        destinationLat = destination?.lat,
        destinationLon = destination?.lon,
        status = status,
        startedAt = startedAt,
        endedAt = endedAt,
        endReason = endReason,
        endSyncState = "synced",
        sampleIntervalSec = sampleIntervalSec,
        uploadIntervalSec = uploadIntervalSec,
        mode = mode,
        maxShareSeconds = maxShareSeconds,
        shareExpiresAt = shareExpiresAt,
        latestPositionAt = latestPositionAt,
        shareRevokedAt = shareRevokedAt
    )

    private fun TripEntity.toSummary() = LocalTripSummary(
        id, title,
        if (originName != null && originLat != null && originLon != null) PlaceMarker(originName, originLat, originLon) else null,
        if (destinationName != null && destinationLat != null && destinationLon != null) PlaceMarker(destinationName, destinationLat, destinationLon) else null,
        status, startedAt, endedAt, sampleIntervalSec, uploadIntervalSec, TrackingMode.fromWire(mode), maxShareSeconds,
        credentials.shareUrl(id)?.takeIf(apiFactory::isSafeShareUrl), shareExpiresAt, latestPositionAt, shareRevokedAt
    )

    private fun preservePendingLocalEnd(existing: TripEntity?, incoming: TripEntity): TripEntity =
        if (existing?.status == "ended" && existing.endSyncState == "pending") {
            incoming.copy(
                status = "ended",
                endedAt = existing.endedAt,
                endReason = existing.endReason,
                endSyncState = "pending"
            )
        } else incoming

    private fun cacheShareUrl(id: String, remoteUrl: String?, expiresAt: String?, revokedAt: String?) {
        val expired = expiresAt?.let { runCatching { !Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false) } == true
        if (revokedAt != null || expired || remoteUrl.isNullOrBlank() || !apiFactory.isSafeShareUrl(remoteUrl)) credentials.removeShareUrl(id)
        else credentials.saveShareUrl(id, remoteUrl)
    }

    companion object {
        const val MIN_INTERVAL = 60
        const val MAX_INTERVAL = 3_600
        const val MIN_SHARE_SECONDS = 3_600
        const val MAX_SHARE_SECONDS = 86_400
    }
}
