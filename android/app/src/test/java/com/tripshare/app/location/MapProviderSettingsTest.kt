package com.tripshare.app.location

import com.tripshare.app.data.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class MapProviderSettingsTest {

    @Test
    fun `either key alone is enough for enhanced mode`() {
        val both = MapProviderSettings.configState("android-key", "web-key")
        assertEquals(MapConfigState.READY, both)
        val androidOnly = MapProviderSettings.configState("android-key", null)
        assertEquals(MapConfigState.READY, androidOnly)
        val webOnly = MapProviderSettings.configState(null, "web-key")
        assertEquals(MapConfigState.READY, webOnly)
        assertEquals(MapProvider.AMAP, MapProviderSettings.resolve(MapProvider.AMAP, androidOnly, builtInAmapAvailable = false))
        assertEquals(MapProvider.AMAP, MapProviderSettings.resolve(MapProvider.AMAP, webOnly, builtInAmapAvailable = false))
    }

    @Test
    fun `missing keys fall back to OSM`() {
        assertEquals(MapConfigState.NOT_CONFIGURED, MapProviderSettings.configState(null, null))
        val blankState = MapProviderSettings.configState("  ", "")
        assertEquals(MapConfigState.NOT_CONFIGURED, blankState)
        assertEquals(MapProvider.OSM, MapProviderSettings.resolve(MapProvider.AMAP, blankState, builtInAmapAvailable = false))
    }

    @Test
    fun `basic mode always stays OSM`() {
        assertEquals(
            MapProvider.OSM,
            MapProviderSettings.resolve(MapProvider.OSM, MapConfigState.READY, builtInAmapAvailable = true)
        )
    }

    @Test
    fun `legacy builds with bundled keys keep enhanced mode working`() {
        assertEquals(
            MapProvider.AMAP,
            MapProviderSettings.resolve(MapProvider.AMAP, MapConfigState.NOT_CONFIGURED, builtInAmapAvailable = true)
        )
    }

    @Test
    fun `map provider wire values round trip`() {
        assertEquals(MapProvider.AMAP, MapProvider.fromWire("AMAP"))
        assertEquals(MapProvider.OSM, MapProvider.fromWire("OSM"))
        assertEquals(MapProvider.OSM, MapProvider.fromWire(null))
        assertEquals(MapProvider.OSM, MapProvider.fromWire("GOOGLE"))
    }
}
