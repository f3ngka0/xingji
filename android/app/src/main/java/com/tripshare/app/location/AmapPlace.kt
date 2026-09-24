package com.tripshare.app.location

import com.tripshare.app.data.PlaceMarker
import com.tripshare.app.data.remote.PlaceTipDto

data class AmapPlace(val name: String, val address: String?, val latWgs84: Double, val lonWgs84: Double) {
    fun asMarker() = PlaceMarker(name, latWgs84, lonWgs84)

    companion object {
        fun fromTip(tip: PlaceTipDto): AmapPlace? {
            val rawLocation = tip.location?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
            val coordinates = rawLocation.split(',')
            if (coordinates.size != 2) return null
            val lonGcj = coordinates[0].toDoubleOrNull() ?: return null
            val latGcj = coordinates[1].toDoubleOrNull() ?: return null
            if (!latGcj.isFinite() || !lonGcj.isFinite()) return null
            val coordinate = CoordinateTransform.gcj02ToWgs84(latGcj, lonGcj)
            val name = tip.name.stringValue() ?: return null
            val address = listOfNotNull(tip.district.stringValue(), tip.address.stringValue())
                .distinct().joinToString(" ").ifBlank { null }
            return AmapPlace(name, address, coordinate.lat, coordinate.lon)
        }

        private fun com.google.gson.JsonElement?.stringValue(): String? =
            this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim()?.takeIf { it.isNotBlank() }
    }
}
