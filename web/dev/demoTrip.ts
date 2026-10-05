// 演示数据：只用 Vite 开发服务器提供，让界面设计不依赖后端就能看到真实排版。
// 生产构建不包含它（只在 serve 模式下注册中间件），真实接口路径完全不受影响。
// 默认行程「天台山·石梁 → 三门县·海游」，30 个位置点，5 分钟采样，状态为共享中。
// 用 `?state=` 可以取到其他关键状态：stale（暂未更新）、ended（已结束）、empty（还没有位置）。

const TOKEN = 'demo';
type DemoState = 'active' | 'stale' | 'ended' | 'empty';
const ORIGIN = { name: '天台山 · 石梁', lat: 29.2022, lon: 121.0518 };
const DESTINATION = { name: '三门县 · 海游', lat: 29.1183, lon: 121.3942 };
const CURRENT = { name: '天台县 · 坦头镇一带', lat: 29.0912, lon: 121.2198 };

const WAYPOINTS: Array<[number, number]> = [
  [29.2022, 121.0518],
  [29.1968, 121.0701],
  [29.1905, 121.0884],
  [29.1831, 121.1052],
  [29.1748, 121.1207],
  [29.1655, 121.1358],
  [29.1552, 121.1499],
  [29.1441, 121.1631],
  [29.1322, 121.1758],
  [29.0912, 121.2198],
];

const SAMPLE_INTERVAL_SEC = 300;
const POINT_COUNT = 30;
const UPLOAD_INTERVAL_SEC = 300;

function pad(value: number) {
  return value < 10 ? `0${value}` : String(value);
}

/** 时间锚点跟着「现在」走：默认最后一个采样点落在 6 分钟前，页面状态是「共享中」。 */
function anchorStart(state: DemoState) {
  const now = Date.now();
  const spanMinutes = POINT_COUNT * SAMPLE_INTERVAL_SEC / 60;
  const trailingMinutes = state === 'stale' ? 46 : state === 'ended' ? 90 : 6;
  return now - (spanMinutes + trailingMinutes) * 60_000;
}

/** 第 n 个采样点：锚点 + n×5 分钟，输出带 +08:00 偏移的 ISO 串。 */
function capturedAt(index: number, state: DemoState) {
  const start = anchorStart(state);
  const time = new Date(start + index * SAMPLE_INTERVAL_SEC * 1000);
  const shifted = new Date(time.getTime() + 8 * 3600_000);
  return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}T${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}:${pad(shifted.getUTCSeconds())}+08:00`;
}

function interpolate(index: number) {
  const span = (WAYPOINTS.length - 1) * 3; // 每个控制点之间插 3 个采样点
  const t = (index / span) * (WAYPOINTS.length - 1);
  const low = Math.min(WAYPOINTS.length - 2, Math.floor(t));
  const ratio = t - low;
  const [latA, lonA] = WAYPOINTS[low];
  const [latB, lonB] = WAYPOINTS[low + 1];
  return {
    lat: Number((latA + (latB - latA) * ratio).toFixed(5)),
    lon: Number((lonA + (lonB - lonA) * ratio).toFixed(5)),
  };
}

function buildPoints(state: DemoState) {
  return Array(POINT_COUNT)
    .fill(0)
    .map((_, offset) => {
      const index = offset + 1;
      const { lat, lon } = interpolate(offset);
      const progress = offset / (POINT_COUNT - 1);
      const speedMps = index === POINT_COUNT
        ? 10.6
        : Number((7.4 + Math.sin(index * 0.9) * 3.6 + progress * 2.2).toFixed(2));
      return {
        id: `p${pad(index)}`,
        lat,
        lon,
        capturedAt: capturedAt(index, state),
        receivedAt: capturedAt(index + 1, state),
        accuracyM: Number((9 + (index % 5) * 1.4).toFixed(1)),
        speedMps,
        speedAccuracyMps: 0.6,
        source: 'fused',
        sequence: index,
        isOutlier: false,
        coordinateSystem: 'WGS84',
      };
    });
}

function buildTrip(state: DemoState) {
  // empty = 行程刚建好、还没有任何位置点：hasPosition 为 false，地图上不该出现起终点与轨迹。
  const points = state === 'empty' ? [] : buildPoints(state);
  const latest = points.length > 0 ? points[points.length - 1] : null;
  const ended = state === 'ended';
  return {
    title: '周末回三门',
    origin: ORIGIN,
    destination: DESTINATION,
    status: ended ? 'ended' : 'active',
    startedAt: capturedAt(0, state),
    endedAt: ended ? capturedAt(POINT_COUNT, state) : null,
    sampleIntervalSec: SAMPLE_INTERVAL_SEC,
    uploadIntervalSec: UPLOAD_INTERVAL_SEC,
    mode: 'standard',
    mapProvider: 'OSM',
    latestPositionAt: latest ? latest.capturedAt : null,
    latestPositionLabel: latest ? CURRENT.name : null,
    pointCount: points.length,
    latestPosition: latest,
  };
}

interface DemoResponse {
  statusCode: number;
  setHeader(name: string, value: string): void;
  end(body?: string): void;
}

const json = (res: DemoResponse, payload: unknown, status = 200) => {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify(payload));
};

/** 处理 /api/v1/public/trips/<token>[/positions]；不是演示 token 时返回 false，交给真实后端。 */
export function handleDemoRequest(url: string | undefined, res: DemoResponse): boolean {
  if (!url) return false;
  const [pathname, search = ''] = url.split('?');
  const match = /^\/api\/v1\/public\/trips\/([^/]+)(\/positions)?\/?$/.exec(pathname);
  if (!match || decodeURIComponent(match[1]) !== TOKEN) return false;

  const query = new URLSearchParams(search);
  const requested = query.get('state');
  const state: DemoState = requested === 'stale' || requested === 'ended' || requested === 'empty' ? requested : 'active';
  const trip = buildTrip(state);

  if (!match[2]) {
    json(res, { trip });
    return true;
  }

  const after = Number(query.get('after') ?? 0) || 0;
  const limit = Math.min(Number(query.get('limit') ?? 500) || 500, 500);
  const points = (state === 'empty' ? [] : buildPoints(state)).filter((point) => point.sequence > after);
  const page = points.slice(0, limit);
  const hasMore = points.length > page.length;
  json(res, {
    points: page,
    nextCursor: page.length > 0 ? page[page.length - 1].sequence : null,
    hasMore,
  });
  return true;
}
