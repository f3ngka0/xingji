import { useCallback, useEffect, useMemo, useState } from 'react';
import { Clock3, Gauge, LocateFixed, MapPin, RefreshCw, Route, ShieldCheck } from 'lucide-react';
import { TripMap } from '../components/TripMap';
import { sortPositions } from '../lib/geo';
import { useTripData } from '../lib/useTripData';
import type { Position } from '../types';

function formatClock(value: string | null) {
  if (!value) return '暂无记录';
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function formatRelative(value: string | null, now: number) {
  if (!value) return '尚未收到位置记录';
  const elapsedSeconds = Math.max(0, Math.floor((now - Date.parse(value)) / 1000));
  if (elapsedSeconds < 60) return '刚刚';
  if (elapsedSeconds < 3600) return `${Math.floor(elapsedSeconds / 60)} 分钟前`;
  if (elapsedSeconds < 86_400) return `${Math.floor(elapsedSeconds / 3600)} 小时前`;
  return `${Math.floor(elapsedSeconds / 86_400)} 天前`;
}

function formatDuration(startedAt: string, endedAt: string | null, now: number) {
  const totalMinutes = Math.max(0, Math.floor(((endedAt ? Date.parse(endedAt) : now) - Date.parse(startedAt)) / 60_000));
  const days = Math.floor(totalMinutes / 1_440);
  const hours = Math.floor((totalMinutes % 1_440) / 60);
  const minutes = totalMinutes % 60;
  return days > 0 ? `${days} 天 ${hours} 小时` : hours > 0 ? `${hours} 小时 ${minutes} 分钟` : `${minutes} 分钟`;
}

function headingFor(originName: string | undefined, destinationName: string | undefined) {
  if (originName && destinationName) return `${originName} → ${destinationName}`;
  if (originName) return `从${originName}出发`;
  return '我的位置共享';
}

function coordinateLabel(position: Position | null) {
  if (!position) return '等待设备上传实际位置';
  return `${position.lat.toFixed(4)}°, ${position.lon.toFixed(4)}°`;
}

type PositionState = 'ended' | 'waiting' | 'fresh' | 'stale';

function positionState(status: 'active' | 'ended', latestAt: string | null, uploadIntervalSec: number, now: number): PositionState {
  if (status === 'ended') return 'ended';
  if (!latestAt) return 'waiting';
  return now - Date.parse(latestAt) > (uploadIntervalSec * 2 + 120) * 1000 ? 'stale' : 'fresh';
}

function stateCopy(state: PositionState) {
  switch (state) {
    case 'ended': return { label: '行程已结束', className: 'status-ended', detail: '已停止采集，仍可查看已记录的轨迹。' };
    case 'waiting': return { label: '等待首个位置', className: 'status-waiting', detail: '设备开始共享后，这里会显示实际采集的位置。' };
    case 'stale': return { label: '位置长时间未更新', className: 'status-stale', detail: '保留最后一次实际位置，不推测当前所在位置。' };
    case 'fresh': return { label: '正在更新', className: 'status-fresh', detail: '位置由设备实际采集并上传。' };
  }
}

export function SharePage({ token }: { token: string }) {
  const { trip, positions, state, error, retry, refresh } = useTripData(token);
  const [now, setNow] = useState(Date.now());
  const [selectedPosition, setSelectedPosition] = useState<Position | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const onSelectPosition = useCallback((position: Position) => setSelectedPosition(position), []);
  const orderedPoints = useMemo(() => sortPositions(positions), [positions]);
  const latestObserved = [...orderedPoints].reverse().find((point) => !point.isOutlier) ?? null;
  const displayPosition = selectedPosition ?? latestObserved;

  const handleRefresh = async () => {
    setRefreshing(true);
    await refresh();
    setRefreshing(false);
  };

  if (state === 'loading' && !trip) {
    return (
      <main className="loading-page">
        <div className="loading-mark"><LocateFixed size={22} /></div>
        <span className="spinner" />
        <p>正在打开共享行程…</p>
      </main>
    );
  }

  if (!trip) {
    return (
      <main className="message-page">
        <section className="message-card">
          <div className="message-icon"><ShieldCheck size={24} /></div>
          <span className="eyebrow">行程共享</span>
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
  const shareState = positionState(trip.status, latestAt, trip.uploadIntervalSec, now);
  const copy = stateCopy(shareState);
  const heading = headingFor(trip.origin?.name, trip.destination?.name);
  const elapsed = formatDuration(trip.startedAt, trip.endedAt, now);

  return (
    <main className="share-shell">
      <header className="topbar">
        <div className="brand-mark"><Route size={19} strokeWidth={2.2} /></div>
        <div className="brand-copy">
          <strong>同行</strong>
          <span>行程位置共享</span>
        </div>
        <div className="private-note"><ShieldCheck size={15} /><span>仅凭链接查看</span></div>
      </header>

      <div className="share-content">
        <TripMap
          key={token}
          token={token}
          trip={trip}
          positions={positions}
          livePositionId={shareState === 'fresh' ? latestObserved?.id ?? null : null}
          loading={state === 'loading'}
          onSelectPosition={onSelectPosition}
          onRetry={() => void retry()}
        />

        <aside className="journey-panel">
          <div className="journey-heading">
            <div className="eyebrow-row"><span className="eyebrow">共享行程</span><span className={`status-pill ${copy.className}`}><i />{copy.label}</span></div>
            <h1>{heading}</h1>
            {trip.destination && trip.origin && <p className="route-caption">起点与目的地为用户设置标记，地图轨迹来自实际位置记录。</p>}
            {!trip.destination && <p className="route-caption">地图展示设备实际采集的位置与轨迹。</p>}
          </div>

          <div className="current-location">
            <div className="location-icon"><MapPin size={19} /></div>
            <div className="location-copy">
              <span>最近记录位置</span>
              <strong>{coordinateLabel(latestObserved)}</strong>
              <small>{latestObserved ? `采集于 ${formatClock(latestObserved.capturedAt)}` : '等待第一条真实位置数据'}</small>
            </div>
          </div>

          <div className="metrics-row">
            <div className="metric-block">
              <span className="metric-icon"><Clock3 size={16} /></span>
              <div><small>最近更新</small><strong>{formatRelative(latestAt, now)}</strong></div>
            </div>
            <div className="metric-block">
              <span className="metric-icon"><Gauge size={16} /></span>
              <div><small>采样时速度</small><strong>{latestObserved?.speedMps === null || !latestObserved ? '暂无速度数据' : `${(latestObserved.speedMps * 3.6).toFixed(1)} km/h`}</strong></div>
            </div>
          </div>

          <div className="detail-strip">
            <div><span>行程时长</span><strong>{elapsed}</strong></div>
            <div><span>位置间隔</span><strong>{trip.sampleIntervalSec >= 60 ? `${Math.round(trip.sampleIntervalSec / 60)} 分钟` : `${trip.sampleIntervalSec} 秒`}</strong></div>
            <div><span>已记录位置</span><strong>{trip.pointCount.toLocaleString('zh-CN')} 个</strong></div>
          </div>

          <div className={`status-explanation ${shareState === 'stale' ? 'status-explanation-warn' : ''}`}>
            <span className={`signal-mark ${copy.className}`}><i /></span>
            <p>{copy.detail}</p>
            <button className="icon-button" type="button" aria-label="刷新行程" onClick={() => void handleRefresh()} disabled={refreshing}>
              <RefreshCw size={16} className={refreshing ? 'spin' : ''} />
            </button>
          </div>
          {error && <p className="sync-note" role="status">暂时无法连接服务器，页面会继续保留已收到的位置。{error}</p>}

          {displayPosition && (
            <section className="point-detail" aria-live="polite">
              <div className="point-detail-heading"><strong>{selectedPosition ? '所选轨迹点' : '最新有效位置'}</strong><span>WGS-84</span></div>
              <div className="point-detail-grid">
                <div><span>采集时间</span><strong>{new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(displayPosition.capturedAt))}</strong></div>
                <div><span>水平精度</span><strong>约 {Math.round(displayPosition.accuracyM)} 米</strong></div>
                <div><span>采样时速度</span><strong>{displayPosition.speedMps === null ? '暂无速度数据' : `${(displayPosition.speedMps * 3.6).toFixed(1)} km/h`}</strong></div>
              </div>
            </section>
          )}

          <footer className="panel-footer"><ShieldCheck size={14} /><span>此页面只读取本次行程的公开位置，不显示查看者信息。</span></footer>
        </aside>
      </div>
    </main>
  );
}
