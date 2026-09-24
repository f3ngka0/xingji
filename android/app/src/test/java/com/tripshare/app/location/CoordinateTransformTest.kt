package com.tripshare.app.location

import com.google.gson.JsonParser
import com.tripshare.app.data.remote.PlaceTipDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateTransformTest {
    @Test
    fun outsideChinaIsPassedThroughWithoutAnExtraOffset() {
        val lat = 40.7128
        val lon = -74.0060
        val converted = CoordinateTransform.gcj02ToWgs84(lat, lon)
        assertEquals(lat, converted.lat, 0.0)
        assertEquals(lon, converted.lon, 0.0)
    }

    @Test
    fun chinaCoordinatesAreConvertedOnceToWgs84() {
        val gcjLat = 31.2304
        val gcjLon = 121.4737
        val converted = CoordinateTransform.gcj02ToWgs84(gcjLat, gcjLon)
        assertNotEquals(gcjLat, converted.lat, 0.001)
        assertNotEquals(gcjLon, converted.lon, 0.001)
        assertTrue(converted.lat in 31.20..31.24)
        assertTrue(converted.lon in 121.43..121.49)
    }

    @Test
    fun invalidInputtipCoordinatesAreSkippedInsteadOfBreakingSearch() {
        val noCoordinate = PlaceTipDto(
            JsonParser.parseString("\"梧州\""), JsonParser.parseString("[]"), JsonParser.parseString("[]"), JsonParser.parseString("[]")
        )
        val valid = PlaceTipDto(
            JsonParser.parseString("\"梧州南站\""), JsonParser.parseString("\"梧州市\""),
            JsonParser.parseString("\"龙圩区\""), JsonParser.parseString("\"111.248,23.477\"")
        )
        assertNull(AmapPlace.fromTip(noCoordinate))
        val place = AmapPlace.fromTip(valid)
        assertNotNull(place)
        assertEquals("梧州南站", place!!.name)
        assertTrue(place.latWgs84 in 23.40..23.50)
        assertTrue(place.lonWgs84 in 111.15..111.30)
    }

    @Test
    fun samplesMustBeNewerThanTheLastRecordedFix() {
        val previous = "2026-09-24T12:00:00Z"
        assertTrue(AmapLocationSampler.isNewerThan("2026-09-24T12:00:01Z", previous))
        assertTrue(!AmapLocationSampler.isNewerThan(previous, previous))
        assertTrue(!AmapLocationSampler.isNewerThan("2026-09-24T11:59:59Z", previous))
        assertTrue(AmapLocationSampler.isNewerThan(previous, null))
        assertTrue(!AmapLocationSampler.isNewerThan("invalid", null))
    }
}
