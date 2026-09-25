import type { ReactNode } from 'react';
import { Clock3, Gauge, MapPin, RefreshCw } from 'lucide-react';
import type { TripUiState } from '../types';

interface TripStatusBadgeProps {
  state: TripUiState;
}

export function TripStatusBadge({ state }: TripStatusBadgeProps) {
  const label = state === 'ACTIVE' ? '共享中' : state === 'STALE' ? '暂未更新' : '已结束';
  return <span className={`status-badge status-${state.toLowerCase()}`}><i aria-hidden="true" />{label}</span>;
}

interface CurrentLocationInfoProps {
  state: TripUiState;
  hasPosition: boolean;
  label: string | null;
}

export function CurrentLocationInfo({ state, hasPosition, label }: CurrentLocationInfoProps) {
  const heading = hasPosition && state === 'ACTIVE' ? '当前位置' : '最近记录位置';
  const value = hasPosition ? label ?? '位置暂未解析' : '暂无位置记录';

  return (
    <section className="info-row location-row" aria-label={heading}>
      <MapPin className="info-icon location-info-icon" size={23} strokeWidth={1.8} aria-hidden="true" />
      <div className="info-copy">
        <span className="info-label">{heading}</span>
        <strong className="info-value">{value}</strong>
      </div>
    </section>
  );
}

interface LastUpdatedInfoProps {
  value: string;
}

export function LastUpdatedInfo({ value }: LastUpdatedInfoProps) {
  return (
    <section className="info-row" aria-label="最近更新">
      <Clock3 className="info-icon" size={23} strokeWidth={1.8} aria-hidden="true" />
      <div className="info-copy">
        <span className="info-label">最近更新</span>
        <strong className="info-value">{value}</strong>
      </div>
    </section>
  );
}

interface SpeedInfoProps {
  speedKmh: number;
}

export function SpeedInfo({ speedKmh }: SpeedInfoProps) {
  return (
    <section className="info-row" aria-label="时速">
      <Gauge className="info-icon" size={23} strokeWidth={1.8} aria-hidden="true" />
      <div className="info-copy">
        <span className="info-label">时速约</span>
        <strong className="info-value">{speedKmh} km/h</strong>
      </div>
    </section>
  );
}

export function MobileTripSheet({ children }: { children: ReactNode }) {
  return (
    <div className="mobile-trip-sheet">
      <span className="sheet-grabber" aria-hidden="true" />
      {children}
    </div>
  );
}

interface TripInfoPanelProps {
  state: TripUiState;
  heading: string;
  locationLabel: string | null;
  hasPosition: boolean;
  lastUpdated: string;
  speedKmh: number | null;
  refreshing: boolean;
  errorNotice: string | null;
  onRefresh: () => void;
}

export function TripInfoPanel({
  state,
  heading,
  locationLabel,
  hasPosition,
  lastUpdated,
  speedKmh,
  refreshing,
  errorNotice,
  onRefresh,
}: TripInfoPanelProps) {
  const caption = state === 'ENDED'
    ? '行程已结束'
    : state === 'STALE'
      ? '当前位置可能不是实时位置'
      : '家人打开链接即可查看你的位置';
  const footer = state === 'ENDED'
    ? '行程已结束，仍可查看历史轨迹。'
    : state === 'STALE'
      ? '当前位置可能不是实时位置。'
      : '位置将随行程自动更新';

  return (
    <aside className="journey-panel" aria-label="行程信息">
      <MobileTripSheet>
        <div className="panel-inner">
          <div className="panel-topline">
            <TripStatusBadge state={state} />
            <button className="refresh-button" type="button" aria-label="刷新行程" onClick={onRefresh} disabled={refreshing}>
              <RefreshCw size={20} strokeWidth={1.8} className={refreshing ? 'spin' : ''} />
            </button>
          </div>

          <header className="journey-heading">
            <h1>{heading}</h1>
            <p className="route-caption">{caption}</p>
          </header>

          <div className="info-list">
            <CurrentLocationInfo state={state} hasPosition={hasPosition} label={locationLabel} />
            <LastUpdatedInfo value={lastUpdated} />
            {speedKmh !== null && <SpeedInfo speedKmh={speedKmh} />}
          </div>

          {errorNotice && <p className="refresh-note" role="status">{errorNotice}</p>}
          <footer className="panel-footer">{footer}</footer>
        </div>
      </MobileTripSheet>
    </aside>
  );
}
