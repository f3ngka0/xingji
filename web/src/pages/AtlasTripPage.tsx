import { useCallback, useEffect, useMemo, useState } from 'react';
import { MapPin, RefreshCw, ShieldCheck } from 'lucide-react';
import { AtlasInfoPanel, AtlasPrivacyNote } from '../components/AtlasInfoPanel';
import { TripMap } from '../components/TripMap';
import { sortPositions } from '../lib/geo';
import { useTripData } from '../lib/useTripData';
import type { Position, TripUiState } from '../types';
import './atlas.css';

// 方向 01「折叠图志」：把只读分享页做成一本摊开的旅行图志。
// 路径是折痕，读数是批注；地图仍是版面上最大的横向带。
// 数据层、地图组件（TripMap / Leaflet / 高德）与状态判定沿用现状，只重做构图与视觉语言。

const TRIP_MONTH_DAY = new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' });
const TRIP_TIME = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' });

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
  const time = TRIP_TIME.format(date);
  const dateKey = (candidate: Date) => `${candidate.getFullYear()}-${candidate.getMonth()}-${candidate.getDate()}`;
  if (dateKey(date) === dateKey(now)) return `今天 ${time}`;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (dateKey(date) === dateKey(yesterday)) return `昨天 ${time}`;
  return `${TRIP_MONTH_DAY.format(date)} ${time}`;
}

function formatExact(value: string | null) {
  if (!value) return '尚未收到位置';
  const date = new Date(value);
  return `${TRIP_MONTH_DAY.format(date)} ${TRIP_TIME.format(date)}`;
}

function tripState(status: 'active' | 'ended', latestAt: string | null, uploadIntervalSec: number, now: number): TripUiState {
  if (status === 'ended') return 'ENDED';
  if (!latestAt || now - Date.parse(latestAt) > (uploadIntervalSec * 2 + 120) * 1000) return 'STALE';
  return 'ACTIVE';
}

function reliableSpeed(position: Position | null, state: TripUiState): number | null {
  if (state !== 'ACTIVE' || !position || position.speedMps === null) return null;
  if (!Number.isFinite(position.speedMps) || position.speedMps < 0.5 || position.speedMps > 111.1) return null;
  if (position.speedAccuracyMps !== null && (!Number.isFinite(position.speedAccuracyMps) || position.speedAccuracyMps > 3)) return null;
  return Math.round(position.speedMps * 3.6);
}

function routePair(originName: string | undefined, destinationName: string | undefined) {
  if (originName && destinationName) return `${originName} → ${destinationName}`;
  if (originName) return `从 ${originName} 出发`;
  if (destinationName) return `前往 ${destinationName}`;
  return null;
}

export function AtlasTripPage({ token }: { token: string }) {
  const { trip, positions, state, error, retry, refresh } = useTripData(token);
  const [now, setNow] = useState(() => Date.now());
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
      <main className="atlas-shell atlas-static">
        <div className="atlas-loading">
          <svg className="atlas-mark" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 21.5s7.2-6.4 7.2-11.4A7.2 7.2 0 0 0 4.8 10c0 5 7.2 11.5 7.2 11.5Z" fill="none" stroke="currentColor" strokeWidth="1.4" />
            <circle cx="12" cy="9.8" r="2.5" fill="currentColor" />
          </svg>
          <span className="spinner" />
          <p>正在打开共享行程…</p>
        </div>
      </main>
    );
  }

  if (!trip) {
    const invalid = error?.includes('无效') || error?.includes('撤销') || error?.includes('过期');
    return (
      <main className="atlas-shell atlas-static">
        <section className="atlas-message" role="status">
          <ShieldCheck size={22} strokeWidth={1.6} aria-hidden="true" />
          <span className="atlas-eyebrow">行迹 · 只读分享</span>
          <h1>{invalid ? '分享链接不可用' : '暂时无法查看行程'}</h1>
          <p>{error ?? '请检查网络连接后重试。'}</p>
          <button className="atlas-refresh" type="button" onClick={() => void retry()}>
            <RefreshCw size={15} strokeWidth={1.8} />
            再试一次
          </button>
        </section>
      </main>
    );
  }

  const latestAt = latestObserved?.capturedAt ?? trip.latestPositionAt;
  const shareState = tripState(trip.status, latestAt, trip.uploadIntervalSec, now);
  const lastUpdated = shareState === 'ENDED' ? formatEndedTime(latestAt) : formatRelative(latestAt, now);
  const speedKmh = reliableSpeed(latestObserved, shareState);
  const refreshNotice = error ? '暂时无法刷新，页面会继续保留已收到的位置。' : null;
  const pair = routePair(trip.origin?.name, trip.destination?.name);
  const title = trip.title?.trim() || '行程位置共享';
  const live = shareState === 'ACTIVE';

  const map = (
    <TripMap
      key={token}
      token={token}
      trip={trip}
      positions={positions}
      currentPositionId={latestObserved?.id ?? null}
      livePositionId={live ? latestObserved?.id ?? null : null}
      positionState={shareState}
      loading={state === 'loading'}
      onRetry={() => void retry()}
    />
  );

  const embeddedMap = new URLSearchParams(window.location.search).get('embed') === 'map';
  if (embeddedMap) return <main className="embedded-map-shell">{map}</main>;

  const stamp = shareState === 'ENDED' ? '行程已结束' : trip.sampleIntervalSec % 60 === 0 ? `${trip.sampleIntervalSec / 60} 分钟` : `${trip.sampleIntervalSec} 秒`;
  const railSteps = Math.min(9, Math.max(3, Math.ceil((trip.pointCount || 1) / 4)));
  const railOn = Math.max(1, Math.round(railSteps * (live ? 0.62 : 1)));

  return (
    <main className={`atlas-shell ${live ? 'is-live' : 'is-idle'}`}>
      <svg className="atlas-defs" aria-hidden="true" focusable="false">
        <defs>
          <pattern id="atlas-hatch" width="9" height="9" patternUnits="userSpaceOnUse" patternTransform="rotate(-18)">
            <line x1="0" y1="0" x2="0" y2="9" stroke="#2C5A4B" strokeOpacity="0.1" strokeWidth="1.6" />
          </pattern>
          <pattern id="atlas-hatch-soft" width="9" height="9" patternUnits="userSpaceOnUse" patternTransform="rotate(-18)">
            <line x1="0" y1="0" x2="0" y2="9" stroke="#8A8578" strokeOpacity="0.14" strokeWidth="1.4" />
          </pattern>
        </defs>
      </svg>

      <header className="atlas-band">
        <span className="atlas-brand">
          <MapPin size={17} strokeWidth={1.8} aria-hidden="true" />
          行迹
        </span>
        <span className="atlas-band-meta">
          <span>分享链接 · 只读</span>
          <span>
            采样间隔 <b>{stamp}</b>
          </span>
          <span>坐标 WGS84</span>
        </span>
        <AtlasPrivacyNote />
      </header>

      <div className="atlas-page">
        <section className="atlas-hero">
          <span className="atlas-eyebrow">行程位置共享</span>
          <h1>{title}</h1>
          {pair && <p className="atlas-hero-pair">{pair}</p>}
          <p className="atlas-hero-caption">
            {shareState === 'ENDED'
              ? '行程已结束，仍可查看历史轨迹。'
              : !latestObserved
                ? '分享链接已开启，等待第一条位置记录。'
                : live
                  ? '位置随行程自动更新；家人打开链接即可查看。'
                  : '当前位置可能不是实时位置，页面会继续尝试同步。'}
          </p>
          <div className="atlas-stamp">
            最近更新
            <b>{lastUpdated}</b>
          </div>
        </section>

        <section className="atlas-band-map" aria-label="行程地图">
          <div className="atlas-band-rail atlas-band-rail-start" aria-hidden="true" />
          <div className="atlas-map-viewport">{map}</div>
          <div className="atlas-band-rail atlas-band-rail-end" aria-hidden="true" />
        </section>

        <section className="atlas-route-mobile" aria-label="路线">
          <span>{trip.origin?.name ?? '起点'}</span>
          <i aria-hidden="true" />
          <span>{trip.destination?.name ?? '终点'}</span>
        </section>
        <AtlasInfoPanel
          state={shareState}
          originName={trip.origin?.name ?? null}
          destinationName={trip.destination?.name ?? null}
          locationLabel={trip.latestPositionLabel ?? null}
          hasPosition={latestObserved !== null}
          lastUpdated={lastUpdated}
          updatedCaption={
            latestAt
              ? `记录于 ${formatExact(latestAt)} · 已收到 ${trip.pointCount} 个位置点`
              : '等待第一条位置记录'
          }
          speedKmh={speedKmh}
          refreshing={refreshing}
          errorNotice={refreshNotice}
          onRefresh={() => void handleRefresh()}
        />

        <footer className="atlas-log">
          <div className="atlas-rail" aria-hidden="true">
            {Array.from({ length: railSteps }, (_, index) => (
              <span
                key={index}
                className={index < railOn - 1 ? 'on' : index === railOn - 1 ? (live ? 'live' : 'on') : ''}
              />
            ))}
          </div>
          <div className="atlas-log-foot">
            <span className="atlas-log-note">
              {trip.startedAt ? `出发于 ${formatExact(trip.startedAt)}` : '行程进行中'}
              {trip.pointCount ? ` · 已记录 ${trip.pointCount} 段` : ''}
            </span>
            <span className="atlas-log-status">
              {shareState === 'ENDED' ? '行程已结束' : live ? '位置持续同步中' : '等待下一次同步'}
            </span>
          </div>
        </footer>
      </div>
    </main>
  );
}
