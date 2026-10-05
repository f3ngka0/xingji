import { Link2, RefreshCw } from 'lucide-react';
import { MobileTripSheet } from './TripInfoPanel';
import type { TripUiState } from '../types';

interface AtlasInfoPanelProps {
  state: TripUiState;
  originName: string | null;
  destinationName: string | null;
  locationLabel: string | null;
  hasPosition: boolean;
  lastUpdated: string;
  updatedCaption: string;
  speedKmh: number | null;
  refreshing: boolean;
  errorNotice: string | null;
  onRefresh: () => void;
}

function statusWord(state: TripUiState, hasPosition: boolean) {
  if (state === 'ACTIVE') return hasPosition ? '共享中' : '等待位置';
  if (state === 'STALE') return '暂未更新';
  return '已结束';
}

export function AtlasInfoPanel({
  state,
  originName,
  destinationName,
  locationLabel,
  hasPosition,
  lastUpdated,
  updatedCaption,
  speedKmh,
  refreshing,
  errorNotice,
  onRefresh,
}: AtlasInfoPanelProps) {
  const live = state === 'ACTIVE';
  const hasSpeed = speedKmh !== null;

  return (
    <aside className={`atlas-sheet ${live ? 'is-live' : 'is-idle'}`} aria-label="行程信息">
      <MobileTripSheet>
        <div className="atlas-sheet-inner">
          <div className="atlas-sheet-top">
            <span className={`atlas-state status-${state.toLowerCase()}`}>
              <i aria-hidden="true" />
              {statusWord(state, hasPosition)}
            </span>
            <button
              className="atlas-refresh"
              type="button"
              aria-label="刷新行程"
              onClick={onRefresh}
              disabled={refreshing}
            >
              <RefreshCw size={16} strokeWidth={1.8} className={refreshing ? 'spin' : ''} />
              刷新行程
            </button>
          </div>

          <div className="atlas-rows">
            <div className={`atlas-row ${live && hasPosition ? 'is-live' : ''}`}>
              <span className="atlas-row-label">当前所在</span>
              <strong className="atlas-row-value">
                {hasPosition ? locationLabel ?? '位置暂未解析' : '暂无位置记录'}
              </strong>
              {originName && destinationName && (
                <span className="atlas-row-sub">
                  {originName} → {destinationName}
                </span>
              )}
            </div>

            <div className="atlas-row">
              <span className="atlas-row-label">最近更新</span>
              <strong className="atlas-row-value mono">{lastUpdated}</strong>
              <span className="atlas-row-sub">{updatedCaption}</span>
            </div>

            {hasSpeed && (
              <div className="atlas-row">
                <span className="atlas-row-label">时速约</span>
                <strong className="atlas-row-value mono">
                  {speedKmh}
                  <em>km/h</em>
                </strong>
                <span className="atlas-row-sub">按最近一次定位读数估算</span>
              </div>
            )}
          </div>

          {errorNotice && (
            <p className="atlas-notice" role="status">
              {errorNotice}
            </p>
          )}
        </div>
      </MobileTripSheet>
    </aside>
  );
}

export function AtlasPrivacyNote() {
  return (
    <span className="atlas-private">
      <Link2 size={13} strokeWidth={1.8} aria-hidden="true" />
      仅凭链接查看
    </span>
  );
}
