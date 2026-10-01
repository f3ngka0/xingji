import { useEffect, useRef, useState } from 'react';
import L, { type Layer, type Map as LeafletMap } from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { MapPin, RefreshCw } from 'lucide-react';
import { drawTripOverlays, loadAMap, type AMapInstance, type AMapOverlay } from '../lib/amap';
import { drawLeafletTripLayers, fitLeafletToObservedPositions } from '../lib/leaflet';
import { toDisplayCoordinate, type MapProviderName } from '../lib/mapCoordinate';
import { providerForTrip, providerFallsBackToOsm } from '../lib/mapProvider';
import { webAmapConfigured } from '../lib/mapEnv';
import type { Position, PublicTrip, TripUiState } from '../types';

interface TripMapProps {
  token: string;
  trip: PublicTrip;
  positions: Position[];
  currentPositionId: string | null;
  livePositionId: string | null;
  positionState: TripUiState;
  loading: boolean;
  onRetry: () => void;
}

type LoadedMapProvider = MapProviderName | 'loading';

export function TripMap({ token, trip, positions, currentPositionId, livePositionId, positionState, loading, onRetry }: TripMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const amapMapRef = useRef<AMapInstance | null>(null);
  const amapOverlaysRef = useRef<AMapOverlay[]>([]);
  const leafletMapRef = useRef<LeafletMap | null>(null);
  const leafletLayersRef = useRef<Layer[]>([]);
  const [mapProvider, setMapProvider] = useState<LoadedMapProvider>('loading');
  const [mapNotice, setMapNotice] = useState<string | null>(null);
  const [mapError, setMapError] = useState<string | null>(null);
  const [mapLoaded, setMapLoaded] = useState(false);
  const [selectedPosition, setSelectedPosition] = useState<Position | null>(null);
  const [mapAttempt, setMapAttempt] = useState(0);

  // The trip renders with the provider recorded when it was created, so the
  // owner's app and this page always agree on the map semantics.
  const requestedProvider = providerForTrip(trip.mapProvider, webAmapConfigured());
  const fallsBackToOsm = providerFallsBackToOsm(trip.mapProvider, webAmapConfigured());

  useEffect(() => {
    let active = true;
    const origin = trip.origin;
    const latestPosition = trip.latestPosition;
    const focus = latestPosition ?? origin;
    setMapLoaded(false);
    setMapProvider('loading');
    setMapError(null);

    const createOpenMap = (notice: string | null) => {
      try {
        if (!active || !containerRef.current || leafletMapRef.current) return;
        const map = L.map(containerRef.current, { zoomControl: false, attributionControl: true, preferCanvas: true });
        // Community OSM tile mirror: reachable from mainland-family networks where
        // tile.openstreetmap.org is not; same OSM data and WGS-84 datum.
        L.tileLayer('https://{s}.tile.openstreetmap.de/{z}/{x}/{y}.png', {
          maxZoom: 19,
          subdomains: 'abc',
          attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        }).addTo(map);
        if (focus) map.setView([focus.lat, focus.lon], latestPosition ? 14 : 12);
        else map.setView([35.8617, 104.1954], 4);
        leafletMapRef.current = map;
        setMapProvider('osm');
        setMapNotice(notice);
        setMapLoaded(true);
      } catch (reason) {
        if (active) setMapError(reason instanceof Error ? reason.message : '开源地图暂时无法初始化。');
      }
    };

    if (requestedProvider === 'osm') {
      createOpenMap(null);
    } else {
      void loadAMap()
        .then((amap) => {
          if (!active || !containerRef.current || amapMapRef.current) return;
          const center = focus
            ? toDisplayCoordinate(focus.lat, focus.lon, 'amap')
            : { lat: 35.8617, lon: 104.1954 };
          amapMapRef.current = new amap.Map(containerRef.current, {
            viewMode: '2D',
            zoom: focus ? 12 : 4,
            center: [center.lon, center.lat],
            resizeEnable: true,
          });
          setMapProvider('amap');
          setMapNotice(null);
          setMapLoaded(true);
        })
        .catch(() => {
          if (!active) return;
          createOpenMap('地图服务暂不可用，已自动切换底图，行程位置仍可查看。');
        });
    }

    return () => {
      active = false;
      amapOverlaysRef.current.forEach((overlay) => overlay.setMap(null));
      amapOverlaysRef.current = [];
      amapMapRef.current?.destroy();
      amapMapRef.current = null;
      leafletLayersRef.current.forEach((layer) => leafletMapRef.current?.removeLayer(layer));
      leafletLayersRef.current = [];
      leafletMapRef.current?.remove();
      leafletMapRef.current = null;
    };
    // Each share token owns one map instance; trip data changes redraw overlays below.
  }, [token, mapAttempt, requestedProvider]);

  useEffect(() => {
    if (!mapLoaded) return;
    if (mapProvider === 'amap' && amapMapRef.current && window.AMap) {
      amapOverlaysRef.current.forEach((overlay) => overlay.setMap(null));
      const drawn = drawTripOverlays(window.AMap, trip, positions, currentPositionId, livePositionId, positionState, (point) => {
        setSelectedPosition(point);
      });
      amapOverlaysRef.current = drawn.overlays;
      amapMapRef.current.add(drawn.overlays);
      if (drawn.observedMarkers.length > 0) {
        amapMapRef.current.setFitView(drawn.observedMarkers, false, [64, 48, 104, 48]);
      } else if (trip.latestPosition) {
        const center = toDisplayCoordinate(trip.latestPosition.lat, trip.latestPosition.lon, 'amap');
        amapMapRef.current.setCenter([center.lon, center.lat]);
        amapMapRef.current.setZoom(15);
      } else if (trip.origin) {
        const center = toDisplayCoordinate(trip.origin.lat, trip.origin.lon, 'amap');
        amapMapRef.current.setCenter([center.lon, center.lat]);
        amapMapRef.current.setZoom(13);
      }
    } else if (mapProvider === 'osm' && leafletMapRef.current) {
      leafletLayersRef.current.forEach((layer) => leafletMapRef.current?.removeLayer(layer));
      const drawn = drawLeafletTripLayers(leafletMapRef.current, trip, positions, currentPositionId, livePositionId, positionState, (point) => {
        setSelectedPosition(point);
      });
      leafletLayersRef.current = drawn.layers;
      const fallback = trip.latestPosition
        ? { lat: trip.latestPosition.lat, lon: trip.latestPosition.lon, zoom: 15 }
        : trip.origin
          ? { lat: trip.origin.lat, lon: trip.origin.lon, zoom: 13 }
          : null;
      fitLeafletToObservedPositions(leafletMapRef.current, drawn.observedPositions, fallback);
      leafletMapRef.current.invalidateSize();
    }
  }, [currentPositionId, livePositionId, mapLoaded, mapProvider, positionState, positions, trip]);

  const infoPosition = selectedPosition;

  return (
    <section className={`map-stage ${mapProvider === 'osm' ? 'map-stage-osm' : ''}`} aria-label="行程地图">
      <div className="map-canvas" ref={containerRef} />
      {mapError ? (
        <div className="map-message" role="status">
          <div className="map-message-icon"><MapPin size={20} /></div>
          <strong>地图暂时无法显示</strong>
          <p>{mapError}</p>
          <button className="text-button" type="button" onClick={() => { setMapError(null); setMapLoaded(false); setMapAttempt((attempt) => attempt + 1); onRetry(); }}>
            <RefreshCw size={15} /> 重试地图和数据
          </button>
        </div>
      ) : !mapLoaded ? (
        <div className="map-loading" role="status">
          <span className="spinner" />
          <span>{loading ? '正在读取行程位置…' : '正在打开地图…'}</span>
        </div>
      ) : positions.length === 0 ? (
        <div className="map-empty-hint">
          <MapPin size={16} /> 尚未收到位置记录
        </div>
      ) : null}
      {mapNotice && mapLoaded && <div className="map-provider-note">{mapNotice}</div>}
      {!mapNotice && fallsBackToOsm && mapLoaded && mapProvider === 'osm' && (
        <div className="map-provider-note">此行程使用高德地图，当前部署未配置高德底图，已用开源地图显示。</div>
      )}
      {infoPosition && (
        <div className="map-point-hint" role="status">
          <strong>位置记录</strong>
          <span>{new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(infoPosition.capturedAt))}</span>
        </div>
      )}
    </section>
  );
}
