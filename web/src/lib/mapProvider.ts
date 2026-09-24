export type MapProvider = 'amap' | 'osm';

export function getPreferredMapProvider(amapJsKey: string | undefined, serverSecurityProxyReady: boolean): MapProvider {
  return amapJsKey?.trim() && serverSecurityProxyReady ? 'amap' : 'osm';
}
