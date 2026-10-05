import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, ShieldCheck } from 'lucide-react';
import { TripMap } from '../components/TripMap';
import { sortPositions } from '../lib/geo';
import { useTripData } from '../lib/useTripData';
import type { Position, TripUiState } from '../types';
import './signal.css';

const EXACT_TIME = new Intl.DateTimeFormat('zh-CN', {
  month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
});

function formatExact(value: string | null) {
  return value ? EXACT_TIME.format(new Date(value)) : null;
}

function relativeParts(value: string | null, now: number): [string, string] {
  if (!value) return ['尚无', '记录'];
  const seconds = Math.max(0, Math.floor((now - Date.parse(value)) / 1000));
  if (seconds < 60) return ['刚刚', '更新'];
  if (seconds < 3600) return [String(Math.floor(seconds / 60)).padStart(2, '0'), '分钟前'];
  if (seconds < 86_400) return [String(Math.floor(seconds / 3600)), '小时前'];
  return [String(Math.floor(seconds / 86_400)), '天前'];
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

export function SignalTripPage({ token }: { token: string }) {
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
    try { await refresh(); } finally { setRefreshing(false); }
  }, [refresh]);

  if (state === 'loading' && !trip) {
    return <main className="signal-shell signal-static"><div className="signal-message"><span className="spinner" /><p>正在打开共享行程…</p></div></main>;
  }

  if (!trip) {
    const invalid = error?.includes('无效') || error?.includes('撤销') || error?.includes('过期');
    return (
      <main className="signal-shell signal-static">
        <section className="signal-message" role="status">
          <ShieldCheck size={28} strokeWidth={1.7} aria-hidden="true" />
          <span className="signal-eyebrow">行迹 · 只读分享</span>
          <h1>{invalid ? '分享链接不可用' : '暂时无法查看行程'}</h1>
          <p>{error ?? '请检查网络连接后重试。'}</p>
          <button type="button" onClick={() => void retry()}><RefreshCw size={16} /> 再试一次</button>
        </section>
      </main>
    );
  }

  const latestAt = latestObserved?.capturedAt ?? trip.latestPositionAt;
  const shareState = tripState(trip.status, latestAt, trip.uploadIntervalSec, now);
  const hasPosition = latestObserved !== null;
  const location = hasPosition ? trip.latestPositionLabel ?? '位置暂未解析' : shareState === 'ENDED' ? '暂无位置记录' : '等待第一条位置';
  const headlinePlace = location.includes('·') ? location.split('·').at(-1)!.trim() : location;
  const [age, ageUnit] = relativeParts(latestAt, now);
  const speedKmh = reliableSpeed(latestObserved, shareState);
  const statusText = shareState === 'ENDED' ? '已结束' : shareState === 'STALE' ? '暂未更新' : hasPosition ? '共享中' : '等待位置';
  const exact = formatExact(latestAt);

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

  if (new URLSearchParams(window.location.search).get('embed') === 'map') {
    return <main className="embedded-map-shell">{map}</main>;
  }

  return (
    <main className="signal-shell">
      <section className="signal-info">
        <header className="signal-top">
          <span className="signal-brand"><i aria-hidden="true" />行迹</span>
          <span className={`signal-status ${shareState.toLowerCase()}`}><i aria-hidden="true" />{statusText}</span>
        </header>

        <div className="signal-lead">
          <span className="signal-eyebrow">{hasPosition ? '最近一次有效定位' : '行程位置共享'}</span>
          <h1><span>{hasPosition ? '最近记录' : shareState === 'ENDED' ? '没有收到' : '等待首条'}</span><span>{hasPosition ? headlinePlace : '位置记录'}</span></h1>
          {hasPosition && <p className="signal-place">{location}</p>}

          <div className={`signal-time ${latestAt ? '' : 'is-empty'}`} aria-label={latestAt ? `最近更新：${age}${ageUnit}` : '尚无位置记录'}>
            <strong>{age}</strong><span>{ageUnit}</span>
          </div>
          <p className="signal-time-note">
            {exact ? `${exact} 记录` : '尚未收到有效位置'}
            {shareState === 'ENDED' ? ' · 行程已结束' : shareState === 'STALE' ? ' · 等待下一次同步' : ' · 位置随行程自动更新'}
          </p>
          {error && <p className="signal-notice" role="status">暂时无法刷新，已收到的位置仍可查看。</p>}
        </div>

        <div className="signal-route">
          <span className="signal-route-label">本次行程</span>
          <div className="signal-route-line">
            <span>{trip.origin?.name ?? '起点未记录'}</span>
            <b aria-hidden="true" />
            <span>{trip.destination?.name ?? '未设置目的地'}</span>
          </div>
          <footer><span>仅凭链接查看</span><span>按实测位置展示</span></footer>
        </div>
      </section>

      <section className="signal-visual" aria-label="行程轨迹">
        <div className="signal-visual-top"><strong>行程轨迹</strong><span>{trip.origin?.name && trip.destination?.name ? `${trip.origin.name} → ${trip.destination.name}` : trip.title || '位置共享'}</span></div>
        <div className="signal-map">{map}</div>
        <div className="signal-visual-foot">
          <span className="signal-speed">{speedKmh === null ? '按最后一次有效定位展示' : <>时速约 <strong>{speedKmh}</strong> <small>km/h</small></>}</span>
          <button className="signal-refresh" type="button" onClick={() => void handleRefresh()} disabled={refreshing}>
            <RefreshCw size={14} className={refreshing ? 'spin' : ''} aria-hidden="true" />
            {refreshing ? '刷新中' : '刷新位置'}
          </button>
        </div>
      </section>
    </main>
  );
}
