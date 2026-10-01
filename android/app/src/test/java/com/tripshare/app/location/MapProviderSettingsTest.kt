package com.tripshare.app.location

import com.tripshare.app.data.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class MapProviderSettingsTest {

    @Test
    fun `enhanced mode only takes effect when both keys are present`() {
        val state = MapProviderSettings.configState("android-key", "web-key")
        assertEquals(MapConfigState.READY, state)
        assertEquals(MapProvider.AMAP, MapProviderSettings.resolve(MapProvider.AMAP, state, builtInAmapAvailable = false))
    }

    @Test
    fun `incomplete or missing keys fall back to OSM`() {
        assertEquals(MapConfigState.INVALID, MapProviderSettings.configState("android-key", null))
        assertEquals(MapConfigState.INVALID, MapProviderSettings.configState("", "web-key"))
        assertEquals(MapConfigState.NOT_CONFIGURED, MapProviderSettings.configState(null, null))
        val blankState = MapProviderSettings.configState("  ", "")
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
