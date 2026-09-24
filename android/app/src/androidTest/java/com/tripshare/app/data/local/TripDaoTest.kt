package com.tripshare.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripDaoTest {
    private lateinit var database: TripDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TripDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun updatingTripSettingsPreservesPreviouslyCapturedPoints() = runBlocking {
        val dao = database.tripDao()
        val trip = sampleTrip()
        val firstPoint = PositionEntity(
            id = "point-1",
            tripId = trip.id,
            lat = 23.5,
            lon = 111.2,
            capturedAt = "2026-09-24T12:00:00Z",
            accuracyM = 8.0,
            speedMps = null,
            speedAccuracyMps = null,
            source = "test"
        )

        dao.putTrip(trip)
        dao.insertPoint(firstPoint)
        assertEquals(listOf(firstPoint.id), dao.allPoints(trip.id).map { it.id })

        dao.putTrip(trip.copy(sampleIntervalSec = 600))

        assertEquals(600, dao.trip(trip.id)?.sampleIntervalSec)
        assertEquals(listOf(firstPoint.id), dao.allPoints(trip.id).map { it.id })
    }

    private fun sampleTrip() = TripEntity(
        id = "trip-1",
        title = "位置共享",
        originName = null,
        originLat = null,
        originLon = null,
        destinationName = null,
        destinationLat = null,
        destinationLon = null,
        status = "active",
        startedAt = "2026-09-24T12:00:00Z",
        endedAt = null,
        endReason = null,
        sampleIntervalSec = 300,
        uploadIntervalSec = 300,
        mode = "standard",
        maxShareSeconds = 86_400,
        shareExpiresAt = null,
        latestPositionAt = null,
        shareRevokedAt = null
    )
}
