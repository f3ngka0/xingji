import L, { type Layer, type Map as LeafletMap, type LatLngExpression } from 'leaflet';
import type { Position, PublicTrip, TripUiState } from '../types';
import { getLongGapThresholdMs, sortPositions } from './geo';
import { toDisplayCoordinate } from './mapCoordinate';

export interface LeafletDrawResult {
  layers: Layer[];
  observedPositions: LatLngExpression[];
}

function positionTitle(position: Position) {
  const captured = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(position.capturedAt));
  return `位置记录 · ${captured}`;
}

function addPlaceMarker(map: LeafletMap, layers: Layer[], place: NonNullable<PublicTrip['origin']>, kind: 'origin' | 'destination') {
  const character = kind === 'origin' ? '起' : '终';
  const point = toDisplayCoordinate(place.lat, place.lon, 'osm');
  const marker = L.marker([point.lat, point.lon], {
    title: place.name,
    icon: L.divIcon({
      className: 'leaflet-place-marker',
      html: `<div class="map-pin map-pin-${kind}"><span>${character}</span></div>`,
      iconSize: [32, 38],
      iconAnchor: [16, 38],
    }),
  }).addTo(map);
  layers.push(marker);
}

export function drawLeafletTripLayers(
  map: LeafletMap,
  trip: PublicTrip,
  positions: Position[],
  currentPositionId: string | null,
  livePositionId: string | null,
  positionState: TripUiState,
  onSelect: (position: Position) => void,
): LeafletDrawResult {
  const layers: Layer[] = [];
  const observedPositions: LatLngExpression[] = [];
  const drawable = sortPositions(positions).filter((point) => !point.isOutlier);

  if (trip.origin) addPlaceMarker(map, layers, trip.origin, 'origin');
  if (trip.destination) addPlaceMarker(map, layers, trip.destination, 'destination');

  let normalSegment: LatLngExpression[] = [];
  let previous: Position | null = null;
  const addPolyline = (path: LatLngExpression[], dashed: boolean) => {
    if (path.length < 2) return;
    const line = L.polyline(path, dashed
      ? { color: '#8b8d89', weight: 3, opacity: 0.8, dashArray: '8 8', lineJoin: 'round', lineCap: 'round' }
      : { color: '#171717', weight: 4, opacity: 0.92, lineJoin: 'round', lineCap: 'round' },
    ).addTo(map);
    layers.push(line);
  };
  const flushNormalSegment = () => {
    addPolyline(normalSegment, false);
    normalSegment = [];
  };

  drawable.forEach((point) => {
    const display = toDisplayCoordinate(point.lat, point.lon, 'osm');
    const coordinate: LatLngExpression = [display.lat, display.lon];
    observedPositions.push(coordinate);
    const isCurrent = point.id === currentPositionId;
    const isLive = point.id === livePositionId;
    const markerClass = isLive
      ? 'map-dot-current'
      : isCurrent && positionState === 'STALE'
        ? 'map-dot-stale'
        : isCurrent && positionState === 'ENDED'
          ? 'map-dot-ended'
          : '';
    const marker = L.marker(coordinate, {
      title: positionTitle(point),
      icon: L.divIcon({
        className: 'leaflet-point-marker',
        html: `<span class="map-dot ${markerClass}"><span></span></span>`,
        iconSize: isLive || isCurrent ? [22, 22] : [14, 14],
        iconAnchor: isLive || isCurrent ? [11, 11] : [7, 7],
      }),
    }).addTo(map);
    marker.on('click', () => onSelect(point));
    layers.push(marker);

    if (previous) {
      const gapMs = Date.parse(point.capturedAt) - Date.parse(previous.capturedAt);
      if (gapMs > getLongGapThresholdMs(trip.sampleIntervalSec)) {
        flushNormalSegment();
        const previousDisplay = toDisplayCoordinate(previous.lat, previous.lon, 'osm');
        addPolyline([[previousDisplay.lat, previousDisplay.lon], coordinate], true);
        normalSegment = [coordinate];
      } else {
        normalSegment.push(coordinate);
      }
    } else {
      normalSegment.push(coordinate);
    }
    previous = point;
  });
  flushNormalSegment();

  return { layers, observedPositions };
}

export function fitLeafletToObservedPositions(
  map: LeafletMap,
  observed: LatLngExpression[],
  fallback: { lat: number; lon: number; zoom: number } | null,
) {
  if (observed.length > 1) {
    const bounds = L.latLngBounds(observed);
    if (bounds.getNorthEast().equals(bounds.getSouthWest())) {
      map.setView(observed[0], 15);
    } else {
      map.fitBounds(bounds, {
        paddingTopLeft: [56, 64],
        paddingBottomRight: [56, 112],
        maxZoom: 16,
      });
    }
    return;
  }
  if (observed.length === 1) {
    map.setView(observed[0], 15);
    return;
  }
  if (fallback) map.setView([fallback.lat, fallback.lon], fallback.zoom);
}
