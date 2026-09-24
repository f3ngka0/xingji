package com.tripshare.app.location

import kotlin.math.*

/** Convert one GCJ-02 coordinate returned by AMap into the WGS-84 wire/storage coordinate. */
object CoordinateTransform {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    data class Coordinate(val lat: Double, val lon: Double)

    fun gcj02ToWgs84(lat: Double, lon: Double): Coordinate {
        if (outsideChina(lat, lon)) return Coordinate(lat, lon)
        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val adjustedLat = dLat * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val adjustedLon = dLon * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return Coordinate(lat - adjustedLat, lon - adjustedLon)
    }

    private fun outsideChina(lat: Double, lon: Double) = lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLon(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return result
    }
}
