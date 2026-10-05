import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link2, MapPin, RefreshCw, ShieldCheck } from 'lucide-react';
import { TripMap } from '../components/TripMap';
import { sortPositions } from '../lib/geo';
import { useTripData } from '../lib/useTripData';
import type { Position, TripUiState } from '../types';
import './inlay.css';

// 方向 02「中轴 · 内嵌读数」：把只读分享页收成一条中轴上的三点一线。
// 标题压在轴上，地图是轴上唯一发亮的一扇窗，两端分列轴的两侧，读数嵌在铭牌里。
// 青色只属于「现在」——除此之外全页只有冷白与灰。
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
  if (!value) return '—';
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

/** 「现在」这一格的说法跟着状态走：读数本身是同一份数据。 */
function nowKeyFor(state: TripUiState) {
  if (state === 'ENDED') return '最终位置';
  if (state === 'STALE') return '最近记录位置';
  return '当前所在';
}

function statusWord(state: TripUiState, hasPosition: boolean) {
  if (state === 'ACTIVE') return hasPosition ? '共享中' : '等待位置';
  if (state === 'STALE') return '暂未更新';
  return '已结束';
}

export function InlayTripPage({ token }: { token: string }) {
  const { trip, positions, state, error, retry, refresh } = useTripData(token);
  const [now, setNow] = useState(() => Date.now());
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  // 深色版面要让手机浏览器的状态栏也跟着变，否则顶部会留一条浅色。
  useEffect(() => {
    const meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) return undefined;
    const previous = meta.getAttribute('content');
    meta.setAttribute('content', '#0a0d13');
    return () => {
      if (previous === null) meta.removeAttribute('content');
      else meta.setAttribute('content', previous);
    };
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
      <main className="inlay-shell inlay-static">
        <div className="inlay-loading">
          <svg className="inlay-mark" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 21.5s7.2-6.4 7.2-11.4A7.2 7.2 0 0 0 4.8 10c0 5 7.2 11.5 7.2 11.5Z" fill="none" stroke="currentColor" strokeWidth="1.4" />
            <circle cx="12" cy="9.8" r="2.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
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
      <main className="inlay-shell inlay-static">
        <section className="inlay-message" role="status">
          <ShieldCheck size={22} strokeWidth={1.6} aria-hidden="true" />
          <span className="inlay-eyebrow">行迹 · 只读分享</span>
          <h1>{invalid ? '分享链接不可用' : '暂时无法查看行程'}</h1>
          <p>{error ?? '请检查网络连接后重试。'}</p>
          <button className="inlay-refresh" type="button" onClick={() => void retry()}>
            <RefreshCw size={14} strokeWidth={1.8} />
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

  const hasPosition = latestObserved !== null;
  const live = shareState === 'ACTIVE' && hasPosition;
  const title = trip.title?.trim() || '行程位置共享';
  const pair = trip.origin?.name && trip.destination?.name
    ? `${trip.origin.name} → ${trip.destination.name}`
    : trip.origin?.name
      ? `从 ${trip.origin.name} 出发`
      : null;
  const locationValue = hasPosition
    ? trip.latestPositionLabel ?? '位置暂未解析'
    : shareState === 'ENDED' ? '暂无位置记录' : '等待第一条位置';

  const nowMeta = hasPosition
    ? `${lastUpdated} 更新 · ${statusWord(shareState, hasPosition)}`
    : shareState === 'ENDED' ? '行程已结束' : '分享链接已开启，等待第一条位置记录';

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

  return (
    <main className={`inlay-shell ${live ? 'is-live' : 'is-idle'}`}>
      <header className="inlay-top">
        <span className="inlay-top-brand">
          <b>行迹</b>
          <span className="inlay-sep">/</span>
          <span>只读分享</span>
        </span>
        <span className="inlay-top-side">
          <span className="inlay-top-note">
            <Link2 size={13} strokeWidth={1.8} aria-hidden="true" />
            仅凭链接查看
          </span>
          <button
            className="inlay-refresh"
            type="button"
            aria-label="刷新行程"
            onClick={() => void handleRefresh()}
            disabled={refreshing}
          >
            <RefreshCw size={13} strokeWidth={1.8} className={refreshing ? 'spin' : ''} />
            刷新
          </button>
        </span>
      </header>

      <div className="inlay-stage">
        <span className="inlay-axis" aria-hidden="true" />

        <div className="inlay-content">
          <section className="inlay-head">
            <span className="inlay-eyebrow">行程位置共享</span>
            <h1 className="inlay-title">{title}</h1>
            {pair && <p className="inlay-pair">{pair}</p>}
          </section>

          <section className={`inlay-now ${live ? 'is-live' : ''}`} aria-label={nowKeyFor(shareState)}>
            <span className="inlay-now-node" aria-hidden="true"><i /></span>
            <span className="inlay-now-key">{nowKeyFor(shareState)}</span>
            <strong className="inlay-now-value">{locationValue}</strong>
            <span className="inlay-now-meta">{nowMeta}</span>
          </section>

          <section className="inlay-band" aria-label="行程地图">
            <div className="inlay-band-frame">{map}</div>
            <span className="inlay-band-line" aria-hidden="true" />
          </section>

          <section className="inlay-ends" aria-label="行程两端">
            <div className="inlay-end is-from">
              <span className="inlay-end-key">起点</span>
              <strong className="inlay-end-value">{trip.origin?.name ?? '以第一个定位点为起点'}</strong>
              <span className="inlay-end-meta">
                {trip.startedAt ? `${formatExact(trip.startedAt)} 出发` : '行程进行中'}
              </span>
            </div>
            <div className="inlay-end is-to">
              <span className="inlay-end-key">终点</span>
              <strong className="inlay-end-value">{trip.destination?.name ?? '尚未设置'}</strong>
              <span className="inlay-end-meta">
                {shareState === 'ENDED'
                  ? trip.endedAt ? `${formatExact(trip.endedAt)} 结束` : '行程已结束'
                  : trip.destination ? '按行程设置显示' : '行程中可随时补充'}
              </span>
            </div>
          </section>

          <section className="inlay-plates" aria-label="读数">
            <div className={`inlay-plate is-left ${live ? 'is-now' : ''}`}>
              <span className="inlay-plate-key">最近更新</span>
              <span className="inlay-plate-val">{lastUpdated}</span>
            </div>
            <div className="inlay-plate is-right">
              {speedKmh !== null ? (
                <>
                  <span className="inlay-plate-key">时速约</span>
                  <span className="inlay-plate-val">{speedKmh}<em>KM/H</em></span>
                </>
              ) : (
                <>
                  <span className="inlay-plate-key">行程状态</span>
                  <span className="inlay-plate-val">{statusWord(shareState, hasPosition)}</span>
                </>
              )}
            </div>
          </section>

          {refreshNotice && <p className="inlay-notice" role="status">{refreshNotice}</p>}
        </div>
      </div>

      <footer className="inlay-foot">
        <span>只读分享 · 不能上传位置或修改行程</span>
        <span>
          最近一次记录 <b>{formatExact(latestAt)}</b>
        </span>
      </footer>
    </main>
  );
}

/** 无效分享链接与失效路径共用同一套外观。 */
export function InlayNotFoundPage({ heading = '页面不存在', detail = '请检查分享链接是否完整。' }: { heading?: string; detail?: string }) {
  return (
    <main className="inlay-shell inlay-static">
      <section className="inlay-message" role="status">
        <MapPin size={22} strokeWidth={1.6} aria-hidden="true" />
        <span className="inlay-eyebrow">行迹 · 只读分享</span>
        <h1>{heading}</h1>
        <p>{detail}</p>
      </section>
    </main>
  );
}
