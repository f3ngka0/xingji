import { PositionsResponseSchema, PublicTripResponseSchema } from '../types';

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * 演示用：开发服务器上的 `/trip/demo?state=ended` 这类地址，把 state 透传给接口，
 * 好把「暂未更新 / 已结束 / 还没有位置」几个状态也看一遍。真实 token 不会带上它。
 */
function demoStateQuery(): string {
  if (typeof window === 'undefined') return '';
  const state = new URLSearchParams(window.location.search).get('state');
  if (!state) return '';
  return `state=${encodeURIComponent(state)}`;
}

function withQuery(path: string, extra: string) {
  if (!extra) return path;
  return path.includes('?') ? `${path}&${extra}` : `${path}?${extra}`;
}

async function readJson(path: string, signal?: AbortSignal): Promise<unknown> {
  const response = await fetch(path, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal,
  });
  let payload: unknown = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }

  if (!response.ok) {
    const errorMessage =
      typeof payload === 'object' && payload !== null && 'error' in payload &&
      typeof payload.error === 'object' && payload.error !== null &&
      'message' in payload.error && typeof payload.error.message === 'string'
        ? payload.error.message
        : response.status === 404
          ? '分享链接无效、已撤销或已过期。'
          : '暂时无法获取行程信息，请稍后重试。';
    throw new ApiError(errorMessage, response.status);
  }
  return payload;
}

export async function getPublicTrip(token: string, signal?: AbortSignal) {
  const raw = await readJson(withQuery(`/api/v1/public/trips/${encodeURIComponent(token)}`, demoStateQuery()), signal);
  return PublicTripResponseSchema.parse(raw).trip;
}

export async function getPositionPage(
  token: string,
  after: number,
  limit = 500,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ after: String(after), limit: String(limit) });
  const raw = await readJson(
    withQuery(`/api/v1/public/trips/${encodeURIComponent(token)}/positions?${query.toString()}`, demoStateQuery()),
    signal,
  );
  return PositionsResponseSchema.parse(raw);
}
