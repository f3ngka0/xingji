import type { Position, PublicTrip, TripUiState } from '../types';
import { getLongGapThresholdMs, sortPositions } from './geo';
import { toDisplayCoordinate } from './mapCoordinate';

export type LngLat = [number, number];

export interface AMapOverlay {
  setMap(map: AMapInstance | null): void;
  on(eventName: string, handler: (event: unknown) => void): void;
}

export interface AMapPixel {
  readonly x: number;
  readonly y: number;
}

export interface AMapInstance {
  add(overlays: AMapOverlay | AMapOverlay[]): void;
  remove(overlays: AMapOverlay | AMapOverlay[]): void;
  setFitView(overlays?: AMapOverlay[], immediately?: boolean, padding?: number[]): void;
  setCenter(position: LngLat): void;
  setZoom(zoom: number): void;
  destroy(): void;
}

interface AMapMarkerOptions {
  position: LngLat;
  content: string;
  title?: string;
  offset?: AMapPixel;
}

interface AMapPolylineOptions {
  path: LngLat[];
  strokeColor: string;
  strokeWeight: number;
  strokeOpacity: number;
  strokeStyle?: 'solid' | 'dashed';
  strokeDasharray?: number[];
  lineJoin?: 'round' | 'bevel' | 'miter';
  lineCap?: 'round' | 'butt' | 'square';
}

export interface AMapNamespace {
  Map: new (container: HTMLElement, options: { viewMode: '2D'; zoom: number; center: LngLat; resizeEnable: boolean }) => AMapInstance;
  Pixel: new (x: number, y: number) => AMapPixel;
  Marker: new (options: AMapMarkerOptions) => AMapOverlay;
  Polyline: new (options: AMapPolylineOptions) => AMapOverlay;
}

declare global {
  interface Window {
    AMap?: AMapNamespace;
    _AMapSecurityConfig?: { serviceHost: string };
  }
}

let amapPromise: Promise<AMapNamespace> | null = null;

export function loadAMap(): Promise<AMapNamespace> {
  if (window.AMap) return Promise.resolve(window.AMap);
  if (amapPromise) return amapPromise;

  const key = import.meta.env.VITE_AMAP_JS_KEY?.trim();
  if (!key) return Promise.reject(new Error('尚未配置高德 JS API Key。'));

  window._AMapSecurityConfig = {
    serviceHost: `${window.location.origin}/_AMapService`,
  };

  amapPromise = new Promise<AMapNamespace>((resolve, reject) => {
    const existingScript = document.querySelector<HTMLScriptElement>('script[data-amap-sdk]');
    const script = existingScript ?? document.createElement('script');
    const onLoad = () => {
      if (window.AMap) resolve(window.AMap);
      else reject(new Error('高德地图 SDK 加载后未能初始化。'));
    };
    const onError = () => reject(new Error('高德地图暂时无法加载，请检查网络或 Key 域名配置。'));
    script.addEventListener('load', onLoad, { once: true });
    script.addEventListener('error', onError, { once: true });
    if (!existingScript) {
      script.dataset.amapSdk = 'true';
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
      script.async = true;
      document.head.append(script);
    }
  }).catch((error: unknown) => {
    amapPromise = null;
    document.querySelector('script[data-amap-sdk]')?.remove();
    throw error;
  });
  return amapPromise;
}

export interface MapDrawResult {
  overlays: AMapOverlay[];
  observedMarkers: AMapOverlay[];
}

function positionTitle(position: Position) {
  const localTime = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(position.capturedAt));
  return `位置记录 · ${localTime}`;
}

function pointMarkerContent(position: Position, currentPositionId: string | null, livePositionId: string | null, positionState: TripUiState) {
  const markerClass = position.id === livePositionId
    ? 'map-dot-current'
    : position.id === currentPositionId && positionState === 'STALE'
      ? 'map-dot-stale'
      : position.id === currentPositionId && positionState === 'ENDED'
        ? 'map-dot-ended'
        : '';
  return `<div class="map-dot ${markerClass}"><span></span></div>`;
}

export function drawTripOverlays(
  amap: AMapNamespace,
  trip: PublicTrip,
  positions: Position[],
  currentPositionId: string | null,
  livePositionId: string | null,
  positionState: TripUiState,
  onSelect: (position: Position) => void,
): MapDrawResult {
  const overlays: AMapOverlay[] = [];
  const observedMarkers: AMapOverlay[] = [];
  const sorted = sortPositions(positions);
  const drawable = sorted.filter((point) => !point.isOutlier);

  const addPlaceMarker = (place: NonNullable<PublicTrip['origin']>, kind: 'origin' | 'destination') => {
    const place2 = toDisplayCoordinate(place.lat, place.lon, 'amap');
    overlays.push(new amap.Marker({
      position: [place2.lon, place2.lat],
      title: place.name,
      offset: new amap.Pixel(-12, -28),
      content: `<div class="map-pin map-pin-${kind}"><span></span></div>`,
    }));
  };

  if (trip.origin) addPlaceMarker(trip.origin, 'origin');
  if (trip.destination) addPlaceMarker(trip.destination, 'destination');

  let normalSegment: LngLat[] = [];
  let previous: Position | null = null;
  const flushNormalSegment = () => {
    if (normalSegment.length > 1) {
      overlays.push(new amap.Polyline({
        path: normalSegment,
        strokeColor: '#171717',
        strokeWeight: 4,
        strokeOpacity: 0.92,
        lineJoin: 'round',
        lineCap: 'round',
      }));
    }
    normalSegment = [];
  };

  drawable.forEach((point) => {
    const convertedRaw = toDisplayCoordinate(point.lat, point.lon, 'amap');
    const converted: LngLat = [convertedRaw.lon, convertedRaw.lat];
    const isCurrent = point.id === currentPositionId;
    const marker = new amap.Marker({
      position: converted,
      title: positionTitle(point),
      offset: new amap.Pixel(isCurrent ? -11 : -7, isCurrent ? -11 : -7),
      content: pointMarkerContent(point, currentPositionId, livePositionId, positionState),
    });
    marker.on('click', () => onSelect(point));
    overlays.push(marker);
    observedMarkers.push(marker);

    if (previous) {
      const gapMs = Date.parse(point.capturedAt) - Date.parse(previous.capturedAt);
      if (gapMs > getLongGapThresholdMs(trip.sampleIntervalSec)) {
        flushNormalSegment();
        const previousRaw = toDisplayCoordinate(previous.lat, previous.lon, 'amap');
        overlays.push(new amap.Polyline({
          path: [[previousRaw.lon, previousRaw.lat], converted],
          strokeColor: '#8b8d89',
          strokeWeight: 3,
          strokeOpacity: 0.8,
          strokeStyle: 'dashed',
          strokeDasharray: [8, 8],
          lineJoin: 'round',
          lineCap: 'round',
        }));
        normalSegment = [converted];
      } else {
        normalSegment.push(converted);
      }
    } else {
      normalSegment.push(converted);
    }
    previous = point;
  });
  flushNormalSegment();

  return { overlays, observedMarkers };
}
