// Run against an already-started disposable server: API_BASE=http://127.0.0.1:3000 node scripts/integration-smoke.mjs
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const base = (process.env.API_BASE || 'http://127.0.0.1:3000').replace(/\/$/, '');

async function request(path, { method = 'GET', token, body } = {}) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const result = response.status === 204 ? null : await response.json();
  return { status: response.status, result };
}

const registration = await request('/api/v1/devices', { method: 'POST', body: { installationId: randomUUID() } });
assert.equal(registration.status, 201);
const credential = registration.result.credential;
assert.ok(credential);

const noDestination = await request('/api/v1/trips', {
  method: 'POST', token: credential,
  body: {
    origin: { name: '测试出发地', lat: 23.393646, lon: 111.222496 },
    destination: null, sampleIntervalSec: 420, uploadIntervalSec: 420,
    mode: 'standard', maxShareSeconds: 3600,
  },
});
assert.equal(noDestination.status, 201);
assert.equal(noDestination.result.trip.destination, null);
const tripId = noDestination.result.trip.id;
const shareToken = new URL(noDestination.result.shareUrl).pathname.split('/').at(-1);
assert.ok(shareToken && shareToken !== tripId);

const now = Date.now();
const older = { id: randomUUID(), lat: 23.393646, lon: 111.222496, capturedAt: new Date(now - 6000).toISOString(), accuracyM: 8, speedMps: null, speedAccuracyMps: null, source: 'integration-test', coordinateSystem: 'WGS84' };
const newer = { ...older, id: randomUUID(), lat: 23.394, capturedAt: new Date(now - 3000).toISOString(), speedMps: 12 };
const firstUpload = await request(`/api/v1/trips/${tripId}/positions`, { method: 'POST', token: credential, body: { points: [newer] } });
assert.equal(firstUpload.status, 200);
assert.deepEqual(firstUpload.result.acceptedIds, [newer.id]);
const lateUpload = await request(`/api/v1/trips/${tripId}/positions`, { method: 'POST', token: credential, body: { points: [older, newer] } });
assert.equal(lateUpload.status, 200);
assert.deepEqual(lateUpload.result.acceptedIds, [older.id]);
assert.deepEqual(lateUpload.result.duplicateIds, [newer.id]);

const publicTrip = await request(`/api/v1/public/trips/${shareToken}`);
assert.equal(publicTrip.status, 200);
assert.equal(publicTrip.result.trip.destination, null);
assert.equal(publicTrip.result.trip.latestPosition.id, newer.id);
assert.equal(publicTrip.result.trip.pointCount, 2);
const history = await request(`/api/v1/public/trips/${shareToken}/positions?after=0&limit=500`);
assert.equal(history.status, 200);
assert.deepEqual(history.result.points.map((point) => point.id), [older.id, newer.id]);
assert.equal(history.result.points[0].speedMps, null);

const restored = await request(`/api/v1/trips/${tripId}`, { token: credential });
assert.equal(restored.result.trip.shareUrl, noDestination.result.shareUrl);
const ended = await request(`/api/v1/trips/${tripId}/end`, { method: 'POST', token: credential });
assert.equal(ended.result.trip.status, 'ended');
assert.equal((await request(`/api/v1/public/trips/${shareToken}`)).status, 200);

const withDestination = await request('/api/v1/trips', {
  method: 'POST', token: credential,
  body: {
    origin: { name: '测试出发地', lat: 23.393646, lon: 111.222496 },
    destination: { name: '测试目的地', lat: 21.48, lon: 109.12 },
    sampleIntervalSec: 300, uploadIntervalSec: 300, mode: 'standard', maxShareSeconds: 3600,
  },
});
assert.equal(withDestination.status, 201);
assert.equal(withDestination.result.trip.destination.name, '测试目的地');
const withDestId = withDestination.result.trip.id;
assert.equal((await request(`/api/v1/trips/${withDestId}/end`, { method: 'POST', token: credential })).status, 200);

assert.equal((await request(`/api/v1/trips/${tripId}/revoke`, { method: 'POST', token: credential })).status, 200);
assert.equal((await request(`/api/v1/public/trips/${shareToken}`)).status, 404);
assert.equal((await request(`/api/v1/trips/${tripId}`, { method: 'DELETE', token: credential })).status, 204);
console.log('Cross-process API smoke passed: optional destination, custom interval, late upload ordering, dedupe, link recovery, end, revoke and delete.');
