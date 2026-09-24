import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getPositionPage, getPublicTrip } from './api';
import { sortPositions } from './geo';
import type { Position, PublicTrip } from '../types';

type LoadState = 'loading' | 'ready' | 'error';

export function useTripData(token: string) {
  const [trip, setTrip] = useState<PublicTrip | null>(null);
  const [positions, setPositions] = useState<Position[]>([]);
  const [state, setState] = useState<LoadState>('loading');
  const [error, setError] = useState<string | null>(null);
  const cursorRef = useRef(0);
  const busyRef = useRef(false);
  const controllerRef = useRef<AbortController | null>(null);

  const mergePoints = useCallback((incoming: Position[]) => {
    setPositions((current) => {
      const byId = new Map(current.map((point) => [point.id, point]));
      let changed = false;
      incoming.forEach((point) => {
        const existing = byId.get(point.id);
        if (!existing || JSON.stringify(existing) !== JSON.stringify(point)) {
          byId.set(point.id, point);
          changed = true;
        }
      });
      if (!changed) return current;
      return sortPositions([...byId.values()]);
    });
    incoming.forEach((point) => {
      cursorRef.current = Math.max(cursorRef.current, point.sequence);
    });
  }, []);

  const loadInitial = useCallback(async () => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setState('loading');
    setError(null);
    cursorRef.current = 0;
    setPositions([]);
    try {
      const nextTrip = await getPublicTrip(token, controller.signal);
      setTrip((current) => current && JSON.stringify(current) === JSON.stringify(nextTrip) ? current : nextTrip);
      const gathered: Position[] = [];
      let after = 0;
      let pageCount = 0;
      do {
        const page = await getPositionPage(token, after, 500, controller.signal);
        gathered.push(...page.points);
        const nextCursor = page.nextCursor;
        pageCount += 1;
        if (!page.hasMore || nextCursor === null || nextCursor <= after || pageCount >= 200) break;
        after = nextCursor;
      } while (!controller.signal.aborted);
      if (nextTrip.latestPosition) gathered.push(nextTrip.latestPosition);
      if (controller.signal.aborted) return;
      mergePoints(gathered);
      setState('ready');
    } catch (reason) {
      if (controller.signal.aborted) return;
      setState('error');
      setError(reason instanceof Error ? reason.message : '暂时无法获取行程信息。');
    }
  }, [mergePoints, token]);

  const refresh = useCallback(async () => {
    if (busyRef.current || document.visibilityState === 'hidden') return;
    busyRef.current = true;
    const controller = new AbortController();
    try {
      const nextTrip = await getPublicTrip(token, controller.signal);
      setTrip((current) => current && JSON.stringify(current) === JSON.stringify(nextTrip) ? current : nextTrip);
      // Overlap a small sequence window to tolerate retries and late-arriving
      // offline points; point IDs keep the client list idempotent.
      const overlapCursor = Math.max(0, cursorRef.current - 20);
      const firstPage = await getPositionPage(token, overlapCursor, 500, controller.signal);
      const gathered = [...firstPage.points];
      let nextCursor = firstPage.nextCursor;
      let after = overlapCursor;
      let pageCount = 0;
      while (firstPage.hasMore && nextCursor !== null && nextCursor > after && pageCount < 20) {
        after = nextCursor;
        const page = await getPositionPage(token, after, 500, controller.signal);
        gathered.push(...page.points);
        nextCursor = page.nextCursor;
        pageCount += 1;
        if (!page.hasMore) break;
      }
      if (nextTrip.latestPosition) gathered.push(nextTrip.latestPosition);
      mergePoints(gathered);
      setError(null);
    } catch (reason) {
      if (!(reason instanceof DOMException && reason.name === 'AbortError')) {
        setError(reason instanceof Error ? reason.message : '同步状态暂时不可用。');
      }
    } finally {
      busyRef.current = false;
    }
  }, [mergePoints, token]);

  useEffect(() => {
    void loadInitial();
    return () => controllerRef.current?.abort();
  }, [loadInitial]);

  const pollIntervalMs = useMemo(
    () => Math.min(60_000, Math.max(15_000, ((trip?.uploadIntervalSec ?? 300) * 1000) / 3)),
    [trip?.uploadIntervalSec],
  );

  useEffect(() => {
    const poll = window.setInterval(() => void refresh(), pollIntervalMs);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.clearInterval(poll);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [pollIntervalMs, refresh]);

  return { trip, positions, state, error, retry: loadInitial, refresh };
}
