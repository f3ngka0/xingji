import { wgs84ToGcj02 } from './geo';

/**
 * Single display-layer coordinate adapter.
 *
 * Storage/wire coordinates are always WGS-84. Each map provider renders in its
 * own datum, so every draw call converts exactly once through this adapter:
 *   OSM  → identity (WGS-84 tiles)
 *   AMAP → WGS-84 → GCJ-02
 * Never convert a coordinate twice; conversion happens only here, never on
 * values that have already passed through this function.
 */
export interface DisplayCoordinate {
  lat: number;
  lon: number;
}

export type MapProviderName = 'osm' | 'amap';

export function toDisplayCoordinate(lat: number, lon: number, provider: MapProviderName): DisplayCoordinate {
  if (provider === 'amap') {
    const [gcjLon, gcjLat] = wgs84ToGcj02(lon, lat);
    return { lat: gcjLat, lon: gcjLon };
  }
  return { lat, lon };
}
