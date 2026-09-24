import { useEffect, useRef, useState } from 'react';
import { MapPin, RefreshCw } from 'lucide-react';
import { drawTripOverlays, loadAMap, type AMapInstance, type AMapOverlay } from '../lib/amap';
import { wgs84ToGcj02 } from '../lib/geo';
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

export function TripMap({ token, trip, positions, livePositionId, loading, onSelectPosition, onRetry }: TripMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<AMapInstance | null>(null);
  const overlaysRef = useRef<AMapOverlay[]>([]);
  const [mapLoaded, setMapLoaded] = useState(false);
  const [mapError, setMapError] = useState<string | null>(null);
  const [selectedPosition, setSelectedPosition] = useState<Position | null>(null);
  const [mapAttempt, setMapAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    const origin = trip.origin;
    const latestPosition = trip.latestPosition;
    void loadAMap()
      .then((amap) => {
        if (!active || !containerRef.current || mapRef.current) return;
        const initial = latestPosition ?? origin;
        const center = initial
          ? wgs84ToGcj02(initial.lon, initial.lat)
            : [116.397, 39.908] as [number, number];
        mapRef.current = new amap.Map(containerRef.current, {
          viewMode: '2D',
          zoom: 5,
          center,
          resizeEnable: true,
        });
        setMapLoaded(true);
      })
      .catch((reason: unknown) => {
        if (active) setMapError(reason instanceof Error ? reason.message : '地图暂时不可用。');
      });
    return () => {
      active = false;
      overlaysRef.current.forEach((overlay) => overlay.setMap(null));
      mapRef.current?.destroy();
      mapRef.current = null;
    };
    // Create the SDK map once per share token; data updates redraw overlays below.
  }, [token, mapAttempt]);

  useEffect(() => {
    if (!mapLoaded || !mapRef.current || !window.AMap) return;
    overlaysRef.current.forEach((overlay) => overlay.setMap(null));
    const drawn = drawTripOverlays(window.AMap, trip, positions, livePositionId, (point) => {
      setSelectedPosition(point);
      onSelectPosition(point);
    });
    overlaysRef.current = drawn.overlays;
    mapRef.current.add(drawn.overlays);
    if (drawn.observedMarkers.length > 0) {
      // Fit only device observations; a manually selected destination must not
      // force a misleading city-to-city viewport.
      mapRef.current.setFitView(drawn.observedMarkers, false, [64, 48, 104, 48]);
    } else if (trip.latestPosition) {
      mapRef.current.setCenter(wgs84ToGcj02(trip.latestPosition.lon, trip.latestPosition.lat));
      mapRef.current.setZoom(15);
    } else if (trip.origin) {
      mapRef.current.setCenter(wgs84ToGcj02(trip.origin.lon, trip.origin.lat));
      mapRef.current.setZoom(13);
    }
  }, [livePositionId, mapLoaded, onSelectPosition, positions, trip]);

  const infoPosition = selectedPosition;

  return (
    <section className="map-stage" aria-label="行程地图">
      <div className="map-canvas" ref={containerRef} />
      {mapError ? (
        <div className="map-message" role="status">
          <div className="map-message-icon"><MapPin size={20} /></div>
          <strong>地图暂时无法显示</strong>
          <p>{mapError}</p>
          <button className="text-button" type="button" onClick={() => { setMapError(null); setMapLoaded(false); setMapAttempt((attempt) => attempt + 1); onRetry(); }}>
            <RefreshCw size={15} /> 重试
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
