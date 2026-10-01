import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { getLongGapThresholdMs, sortPositions, wgs84ToGcj02 } from '../.test-dist/lib/geo.js';
import { providerForTrip, providerFallsBackToOsm } from '../.test-dist/lib/mapProvider.js';
import { PositionsResponseSchema, PublicTripResponseSchema, PublicTripSchema } from '../.test-dist/types.js';

function point(id, capturedAt) {
  return {
    id,
    lat: 39.915,
    lon: 116.404,
    capturedAt,
    receivedAt: capturedAt,
    accuracyM: 12,
    speedMps: null,
    speedAccuracyMps: null,
    source: 'gps',
    sequence: Number(id.replace(/\D/g, '')) || 0,
    isOutlier: false,
    coordinateSystem: 'WGS84',
  };
}

test('converts WGS-84 coordinates in China to GCJ-02 for AMap', () => {
  const [longitude, latitude] = wgs84ToGcj02(116.404, 39.915);
  assert.ok(longitude > 116.404);
  assert.ok(latitude > 39.915);
  assert.ok(Math.abs(longitude - 116.410) < 0.001);
  assert.ok(Math.abs(latitude - 39.916) < 0.001);
});

test('leaves coordinates outside the China transform area unchanged', () => {
  assert.deepEqual(wgs84ToGcj02(-73.9857, 40.7484), [-73.9857, 40.7484]);
});

test('sorts late offline points by capture time and uses ID for ties', () => {
  const points = [
    point('point-3', '2026-05-01T10:00:00Z'),
    point('point-2', '2026-05-01T09:00:00Z'),
    point('point-1', '2026-05-01T09:00:00Z'),
  ];
  assert.deepEqual(sortPositions(points).map((item) => item.id), ['point-1', 'point-2', 'point-3']);
});

test('uses at least five minutes as the dashed-gap threshold', () => {
  assert.equal(getLongGapThresholdMs(60), 300_000);
  assert.equal(getLongGapThresholdMs(300), 720_000);
});

test('accepts a trip without an origin or destination', () => {
  const trip = PublicTripSchema.parse({
    title: '我的位置共享',
    origin: null,
    destination: null,
    status: 'active',
    startedAt: '2026-05-01T08:00:00Z',
    endedAt: null,
    sampleIntervalSec: 300,
    uploadIntervalSec: 300,
    mode: 'standard',
    latestPositionAt: null,
    pointCount: 0,
    latestPosition: null,
  });
  assert.equal(trip.destination, null);
  assert.equal(trip.origin, null);
});

test('accepts the public API fixture without private trip identifiers', async () => {
  const tripFixture = JSON.parse(await readFile(new URL('./fixtures/public-trip.json', import.meta.url), 'utf8'));
  const positionsFixture = JSON.parse(await readFile(new URL('./fixtures/public-positions.json', import.meta.url), 'utf8'));

  const tripResponse = PublicTripResponseSchema.parse(tripFixture);
  const positionsResponse = PositionsResponseSchema.parse(positionsFixture);

  assert.equal(tripResponse.trip.destination, null);
  assert.equal(tripResponse.trip.latestPosition?.id, 'point-001');
  assert.equal(Object.hasOwn(tripResponse.trip.latestPosition ?? {}, 'tripId'), false);
  assert.equal(positionsResponse.points[0]?.sequence, 1);
  assert.equal(Object.hasOwn(positionsResponse.points[0] ?? {}, 'tripId'), false);
});

test('renders each trip with the provider recorded at creation time', () => {
  assert.equal(providerForTrip(undefined, true), 'osm');
  assert.equal(providerForTrip('OSM', true), 'osm');
  assert.equal(providerForTrip('AMAP', false), 'osm');
  assert.equal(providerForTrip('AMAP', true), 'amap');
  assert.equal(providerFallsBackToOsm('AMAP', false), true);
  assert.equal(providerFallsBackToOsm('AMAP', true), false);
  assert.equal(providerFallsBackToOsm('OSM', false), false);
});
