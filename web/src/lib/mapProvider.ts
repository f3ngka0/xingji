export type MapProvider = 'amap' | 'osm';

/**
 * A trip is always rendered with the provider recorded at creation time.
 * `amapReady` reflects whether this deployment can load AMap assets; if it
 * cannot, an AMAP trip falls back to OSM (WGS-84 stays correct on OSM, so no
 * coordinate rewrite is needed).
 */
export function providerForTrip(tripProvider: 'OSM' | 'AMAP' | undefined, amapReady: boolean): MapProvider {
  if ((tripProvider ?? 'OSM') === 'AMAP' && amapReady) return 'amap';
  return 'osm';
}

export function providerFallsBackToOsm(tripProvider: 'OSM' | 'AMAP' | undefined, amapReady: boolean): boolean {
  return (tripProvider ?? 'OSM') === 'AMAP' && !amapReady;
}
