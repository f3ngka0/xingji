import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link2, MapPin, RefreshCw, ShieldCheck } from 'lucide-react';
import { TripMap } from '../components/TripMap';
import { TripInfoPanel } from '../components/TripInfoPanel';
import { sortPositions } from '../lib/geo';
import { useTripData } from '../lib/useTripData';
import type { Position, TripUiState } from '../types';

function formatRelative(value: string | null, now: number) {
  if (!value) return '尚未收到位置';
  const elapsedSeconds = Math.max(0, Math.floor((now - Date.parse(value)) / 1000));
  if (elapsedSeconds < 60) return '刚刚';
  if (elapsedSeconds < 3600) return `${Math.floor(elapsedSeconds / 60)} 分钟前`;
  if (elapsedSeconds < 86_400) return `${Math.floor(elapsedSeconds / 3600)} 小时前`;
  return `${Math.floor(elapsedSeconds / 86_400)} 天前`;
}

function formatEndedTime(value: string | null) {
  if (!value) return '尚未收到位置';
  const date = new Date(value);
  const now = new Date();
  const time = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(date);
  const dateKey = (candidate: Date) => `${candidate.getFullYear()}-${candidate.getMonth()}-${candidate.getDate()}`;
  if (dateKey(date) === dateKey(now)) return `今天 ${time}`;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (dateKey(date) === dateKey(yesterday)) return `昨天 ${time}`;
  const day = new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(date);
  return `${day} ${time}`;
}

function headingFor(originName: string | undefined, destinationName: string | undefined, fallback: string) {
  if (originName && destinationName) return `${originName} → ${destinationName}`;
  if (originName) return `从${originName}出发`;
  return fallback;
}

function tripState(status: 'active' | 'ended', latestAt: string | null, uploadIntervalSec: number, now: number): TripUiState {
  if (status === 'ended') return 'ENDED';
  if (!latestAt || now - Date.parse(latestAt) > (uploadIntervalSec * 2 + 120) * 1000) return 'STALE';
  return 'ACTIVE';
}

function reliableSpeed(position: Position | null, state: TripUiState): number | null {
  if (state !== 'ACTIVE' || !position || position.speedMps === null) return null;
  // Near-zero readings are noise; hide the row instead of showing 0 km/h.
  if (!Number.isFinite(position.speedMps) || position.speedMps < 0.5 || position.speedMps > 111.1) return null;
  if (position.speedAccuracyMps !== null && (!Number.isFinite(position.speedAccuracyMps) || position.speedAccuracyMps > 3)) return null;
  return Math.round(position.speedMps * 3.6);
}

export function SharePage({ token }: { token: string }) {
  const { trip, positions, state, error, retry, refresh } = useTripData(token);
  const [now, setNow] = useState(Date.now());
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const orderedPoints = useMemo(() => sortPositions(positions), [positions]);
  const latestObserved = [...orderedPoints].reverse().find((point) => !point.isOutlier) ?? null;

  const handleRefresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await refresh();
    } finally {
      setRefreshing(false);
    }
  }, [refresh]);

  if (state === 'loading' && !trip) {
    return (
      <main className="loading-page">
        <span className="loading-brand"><MapPin size={25} fill="currentColor" strokeWidth={1.5} /></span>
        <span className="spinner" />
        <p>正在打开共享行程…</p>
      </main>
    );
  }

  if (!trip) {
    return (
      <main className="message-page">
        <section className="message-card">
          <ShieldCheck className="message-icon" size={25} strokeWidth={1.6} />
          <span className="eyebrow">行迹</span>
          <h1>{error?.includes('无效') || error?.includes('撤销') || error?.includes('过期') ? '分享链接不可用' : '暂时无法查看行程'}</h1>
          <p>{error ?? '请检查网络连接后重试。'}</p>
          <button className="primary-button" type="button" onClick={() => void retry()}>
            <RefreshCw size={16} /> 再试一次
          </button>
        </section>
      </main>
    );
  }

  const latestAt = latestObserved?.capturedAt ?? trip.latestPositionAt;
  const shareState = tripState(trip.status, latestAt, trip.uploadIntervalSec, now);
  const heading = headingFor(trip.origin?.name, trip.destination?.name, trip.title || '行程位置共享');
  const lastUpdated = shareState === 'ENDED' ? formatEndedTime(latestAt) : formatRelative(latestAt, now);
  const speedKmh = reliableSpeed(latestObserved, shareState);
  const refreshNotice = error ? '暂时无法刷新，页面会继续保留已收到的位置。' : null;
  const embeddedMap = new URLSearchParams(window.location.search).get('embed') === 'map';
  const map = (
    <TripMap
      key={token}
      token={token}
      trip={trip}
      positions={positions}
      currentPositionId={latestObserved?.id ?? null}
      livePositionId={shareState === 'ACTIVE' ? latestObserved?.id ?? null : null}
      positionState={shareState}
      loading={state === 'loading'}
      onRetry={() => void retry()}
    />
  );

  if (embeddedMap) return <main className="embedded-map-shell">{map}</main>;

  return (
    <main className="share-shell">
      <header className="topbar">
        <div className="brand-lockup">
          <MapPin className="brand-icon" size={28} fill="currentColor" strokeWidth={1.6} aria-hidden="true" />
          <strong>行迹</strong>
        </div>
        <div className="private-note"><Link2 size={17} strokeWidth={1.8} /><span>仅凭链接查看</span></div>
      </header>

      <div className="share-content">
        {map}

        <TripInfoPanel
          state={shareState}
          heading={heading}
          locationLabel={trip.latestPositionLabel ?? null}
          hasPosition={latestObserved !== null}
          lastUpdated={lastUpdated}
          speedKmh={speedKmh}
          refreshing={refreshing}
          errorNotice={refreshNotice}
          onRefresh={() => void handleRefresh()}
        />
      </div>
    </main>
  );
}
