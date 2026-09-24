import { PositionsResponseSchema, PublicTripResponseSchema } from '../types';

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
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
  const raw = await readJson(`/api/v1/public/trips/${encodeURIComponent(token)}`, signal);
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
    `/api/v1/public/trips/${encodeURIComponent(token)}/positions?${query.toString()}`,
    signal,
  );
  return PositionsResponseSchema.parse(raw);
}
