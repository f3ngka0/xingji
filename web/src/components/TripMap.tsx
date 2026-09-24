import { useEffect, useRef, useState } from 'react';
import L, { type Layer, type Map as LeafletMap } from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { MapPin, RefreshCw } from 'lucide-react';
import { drawTripOverlays, loadAMap, type AMapInstance, type AMapOverlay } from '../lib/amap';
import { fitLeafletToObservedPositions, drawLeafletTripLayers } from '../lib/leaflet';
import { wgs84ToGcj02 } from '../lib/geo';
import { getPreferredMapProvider, type MapProvider } from '../lib/mapProvider';
import type { Position, PublicTrip } from '../types';

interface TripMapProps {
  token: string;
  trip: PublicTrip;
  positions: Position[];
  livePositionId: string | null;
  loading: boolean;
  onSelectPosition: (position: Position) => void;
  onRetry: () => void;
}

type LoadedMapProvider = MapProvider | 'loading';

export function TripMap({ token, trip, positions, livePositionId, loading, onSelectPosition, onRetry }: TripMapProps) {
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

  useEffect(() => {
    let active = true;
    const origin = trip.origin;
    const latestPosition = trip.latestPosition;
    const focus = latestPosition ?? origin;
    const amapKey = import.meta.env.VITE_AMAP_JS_KEY?.trim();
    const securityProxyReady = import.meta.env.VITE_AMAP_SECURITY_PROXY_ENABLED === 'true';
    const preferredProvider = getPreferredMapProvider(amapKey, securityProxyReady);
    setMapLoaded(false);
    setMapProvider('loading');
    setMapError(null);

    const createOpenMap = (notice: string | null) => {
      try {
        if (!active || !containerRef.current || leafletMapRef.current) return;
        const map = L.map(containerRef.current, { zoomControl: true, attributionControl: true, preferCanvas: true });
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
          maxZoom: 19,
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

    if (preferredProvider === 'osm') {
      createOpenMap(null);
    } else {
      void loadAMap()
        .then((amap) => {
          if (!active || !containerRef.current || amapMapRef.current) return;
          const center = focus
            ? wgs84ToGcj02(focus.lon, focus.lat)
            : [104.1954, 35.8617] as [number, number];
          amapMapRef.current = new amap.Map(containerRef.current, {
            viewMode: '2D',
            zoom: focus ? 12 : 4,
            center,
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
  }, [token, mapAttempt]);

  useEffect(() => {
    if (!mapLoaded) return;
    if (mapProvider === 'amap' && amapMapRef.current && window.AMap) {
      amapOverlaysRef.current.forEach((overlay) => overlay.setMap(null));
      const drawn = drawTripOverlays(window.AMap, trip, positions, livePositionId, (point) => {
        setSelectedPosition(point);
        onSelectPosition(point);
      });
      amapOverlaysRef.current = drawn.overlays;
      amapMapRef.current.add(drawn.overlays);
      if (drawn.observedMarkers.length > 0) {
        amapMapRef.current.setFitView(drawn.observedMarkers, false, [64, 48, 104, 48]);
      } else if (trip.latestPosition) {
        amapMapRef.current.setCenter(wgs84ToGcj02(trip.latestPosition.lon, trip.latestPosition.lat));
        amapMapRef.current.setZoom(15);
      } else if (trip.origin) {
        amapMapRef.current.setCenter(wgs84ToGcj02(trip.origin.lon, trip.origin.lat));
        amapMapRef.current.setZoom(13);
      }
    } else if (mapProvider === 'osm' && leafletMapRef.current) {
      leafletLayersRef.current.forEach((layer) => leafletMapRef.current?.removeLayer(layer));
      const drawn = drawLeafletTripLayers(leafletMapRef.current, trip, positions, livePositionId, (point) => {
        setSelectedPosition(point);
        onSelectPosition(point);
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
  }, [livePositionId, mapLoaded, mapProvider, onSelectPosition, positions, trip]);

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
      {infoPosition && (
        <div className="map-point-hint" role="status">
          <strong>{new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(infoPosition.capturedAt))}</strong>
          <span>精度约 {Math.round(infoPosition.accuracyM)} 米</span>
          <span>{infoPosition.speedMps === null ? '暂无速度数据' : `${(infoPosition.speedMps * 3.6).toFixed(1)} km/h`}</span>
        </div>
      )}
      <div className="map-legend" aria-label="地图标记说明">
        <span><i className={`legend-dot ${livePositionId ? 'legend-current' : ''}`} />{livePositionId ? '当前有效位置' : '位置记录'}</span>
        {trip.origin && <span><i className="legend-pin legend-origin" />出发地</span>}
        {trip.destination && <span><i className="legend-pin legend-destination" />目的地</span>}
      </div>
    </section>
  );
}
