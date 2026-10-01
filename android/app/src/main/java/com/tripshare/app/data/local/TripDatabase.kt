package com.tripshare.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originName: String?,
    val originLat: Double?,
    val originLon: Double?,
    val destinationName: String?,
    val destinationLat: Double?,
    val destinationLon: Double?,
    val status: String,
    val startedAt: String,
    val endedAt: String?,
    val endReason: String?,
    val endSyncState: String = "synced",
    val sampleIntervalSec: Int,
    val uploadIntervalSec: Int,
    val mode: String,
    val maxShareSeconds: Int,
    val mapProvider: String = "OSM",
    val latestPositionLabel: String? = null,
    val shareExpiresAt: String?,
    val latestPositionAt: String?,
    val shareRevokedAt: String?
)

@Entity(
    tableName = "positions",
    primaryKeys = ["id", "tripId"],
    foreignKeys = [ForeignKey(
        entity = TripEntity::class,
        parentColumns = ["id"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["tripId", "capturedAt"]), Index(value = ["tripId", "syncState", "capturedAt"])]
)
data class PositionEntity(
    val id: String,
    val tripId: String,
    val lat: Double,
    val lon: Double,
    val capturedAt: String,
    val accuracyM: Double,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val source: String?,
    val coordinateSystem: String = "WGS84",
    val receivedAt: String? = null,
    val sequence: Long? = null,
    val syncState: String = "pending",
    val syncError: String? = null
)

@Dao
interface TripDao {
    @Update
    suspend fun updateTrip(trip: TripEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrip(trip: TripEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoint(point: PositionEntity): Long

    @Query("SELECT * FROM trips WHERE id = :id LIMIT 1")
    suspend fun trip(id: String): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'active' ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeTrip(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    suspend fun allTrips(): List<TripEntity>

    @Query("SELECT (SELECT COUNT(*) FROM trips) + (SELECT COUNT(*) FROM positions)")
    suspend fun localRecordCount(): Int

    @Query("SELECT * FROM positions WHERE tripId = :tripId ORDER BY capturedAt ASC, id ASC")
    fun observePoints(tripId: String): Flow<List<PositionEntity>>

    @Query("SELECT * FROM positions WHERE tripId = :tripId ORDER BY capturedAt ASC, id ASC")
    suspend fun allPoints(tripId: String): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE tripId = :tripId ORDER BY capturedAt DESC, id DESC LIMIT 1")
    suspend fun latestPoint(tripId: String): PositionEntity?

    @Query("SELECT * FROM positions WHERE tripId = :tripId AND syncState = 'pending' ORDER BY capturedAt ASC LIMIT :limit")
    suspend fun pendingPoints(tripId: String, limit: Int): List<PositionEntity>

    @Query("UPDATE positions SET syncState = 'synced', syncError = NULL WHERE tripId = :tripId AND id IN (:ids)")
    suspend fun markSynced(tripId: String, ids: List<String>)

    @Query("UPDATE positions SET syncState = 'rejected', syncError = :error WHERE tripId = :tripId AND id = :id")
    suspend fun markRejected(tripId: String, id: String, error: String)

    @Query("UPDATE positions SET sequence = :sequence, receivedAt = :receivedAt WHERE tripId = :tripId AND id = :id")
    suspend fun updateServerMetadata(tripId: String, id: String, sequence: Long?, receivedAt: String?)

    @Query("SELECT COUNT(*) FROM positions WHERE tripId = :tripId AND syncState = 'pending'")
    fun observePendingCount(tripId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM positions WHERE tripId = :tripId AND syncState = 'rejected'")
    fun observeRejectedCount(tripId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM positions WHERE tripId = :tripId AND syncState = 'pending'")
    suspend fun pendingCount(tripId: String): Int

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: String)

    @Query("UPDATE trips SET status = :status, endedAt = :endedAt, endReason = :endReason, endSyncState = :endSyncState WHERE id = :id")
    suspend fun setEnded(id: String, status: String, endedAt: String, endReason: String?, endSyncState: String)

    @Query("UPDATE trips SET endSyncState = 'synced' WHERE id = :id")
    suspend fun markEndSynced(id: String)

    @Query("UPDATE trips SET sampleIntervalSec = :sample, uploadIntervalSec = :upload, mode = :mode, maxShareSeconds = :maxShareSeconds WHERE id = :id")
    suspend fun updateSettings(id: String, sample: Int, upload: Int, mode: String, maxShareSeconds: Int)

    @Query("UPDATE trips SET shareRevokedAt = :revokedAt WHERE id = :id")
    suspend fun setRevoked(id: String, revokedAt: String)

    @Transaction
    suspend fun putTrip(trip: TripEntity) {
        if (updateTrip(trip) == 0) insertTrip(trip)
    }

    @Transaction
    suspend fun saveTripAndPoint(trip: TripEntity, point: PositionEntity) {
        putTrip(trip)
        insertPoint(point)
    }
}

@Database(entities = [TripEntity::class, PositionEntity::class], version = 2, exportSchema = true)
abstract class TripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile private var instance: TripDatabase? = null
        fun get(context: Context): TripDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TripDatabase::class.java, "trip-share.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }

        /** Trips created before map providers existed always render as OSM (WGS-84 tiles). */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN mapProvider TEXT NOT NULL DEFAULT 'OSM'")
                db.execSQL("ALTER TABLE trips ADD COLUMN latestPositionLabel TEXT")
            }
        }
    }
}
