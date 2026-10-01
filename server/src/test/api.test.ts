import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { createApp } from "../app";
import { readConfig } from "../config";
import { openDatabase } from "../database";
import type Database from "better-sqlite3";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { randomUUID } from "node:crypto";
import { labelFromRegeo } from "../geocode";

interface Context {
  db: Database.Database;
  server: Server;
  baseUrl: string;
  deviceId: string;
  credential: string;
}

async function context(): Promise<Context> {
  const db = openDatabase(":memory:");
  const config = readConfig({
    NODE_ENV: "test",
    DB_PATH: ":memory:",
    PUBLIC_BASE_URL: "https://share.example",
    DEFAULT_SAMPLE_INTERVAL_SEC: "300",
    DEFAULT_MAX_SHARE_SECONDS: "86400",
    DEFAULT_SHARE_TTL_SECONDS: "2592000",
    DATA_RETENTION_DAYS: "90",
    REQUIRE_HTTPS: "false",
    TRUST_PROXY_HOPS: "0",
    AMAP_JS_SECURITY_CODE: "server-test-secret"
  });
  const app = createApp(db, config);
  const server = await new Promise<Server>((resolve) => {
    const listening = app.listen(0, "127.0.0.1", () => resolve(listening));
  });
  const address = server.address() as AddressInfo;
  const baseUrl = `http://127.0.0.1:${address.port}`;
  const registration = await fetch(`${baseUrl}/api/v1/devices`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ installationId: randomUUID() })
  });
  assert.equal(registration.status, 201);
  const registrationBody = await registration.json() as { deviceId: string; credential: string };
  return { db, server, baseUrl, deviceId: registrationBody.deviceId, credential: registrationBody.credential };
}

async function close(ctx: Context): Promise<void> {
  await new Promise<void>((resolve, reject) => ctx.server.close((err) => err ? reject(err) : resolve()));
  ctx.db.close();
}

async function json(ctx: Context, path: string, options: RequestInit = {}) {
  const headers = new Headers(options.headers);
  if (!headers.has("content-type") && options.body !== undefined) headers.set("content-type", "application/json");
  const response = await fetch(`${ctx.baseUrl}${path}`, { ...options, headers });
  return { response, body: response.status === 204 ? null : await response.json() as any };
}

async function createTrip(ctx: Context, body: Record<string, unknown> = {}) {
  return json(ctx, "/api/v1/trips", {
    method: "POST",
    headers: { authorization: `Bearer ${ctx.credential}` },
    body: JSON.stringify({ ...body })
  });
}

function management(ctx: Context, method = "GET"): HeadersInit {
  return { authorization: `Bearer ${ctx.credential}` };
}

let ctx: Context;
before(async () => { ctx = await context(); });
after(async () => { if (ctx) await close(ctx); });

test("health and device credentials are usable once and never stored in plain text", async () => {
  const health = await fetch(`${ctx.baseUrl}/healthz`);
  assert.equal(health.status, 200);
  const installationId = (ctx.db.prepare("SELECT installation_id FROM devices WHERE id = ?").get(ctx.deviceId) as { installation_id: string }).installation_id;
  const duplicateRegistration = await json(ctx, "/api/v1/devices", {
    method: "POST",
    body: JSON.stringify({ installationId })
  });
  assert.equal(duplicateRegistration.response.status, 200);
  assert.equal("credential" in duplicateRegistration.body, false);
  const stored = ctx.db.prepare("SELECT credential_hash FROM devices WHERE id = ?").get(ctx.deviceId) as { credential_hash: string };
  assert.notEqual(stored.credential_hash, ctx.credential);
});

test("trip creation accepts absent or explicit-null destination and keeps markers distinct", async () => {
  const absent = await createTrip(ctx, { origin: { name: "梧州南站", lat: 23.43, lon: 111.25 } });
  assert.equal(absent.response.status, 201);
  assert.equal(absent.body.trip.destination, null);
  assert.equal(absent.body.trip.title, "从梧州南站出发");
  const token = new URL(absent.body.shareUrl).pathname.split("/").at(-1)!;
  const publicTrip = await json(ctx, `/api/v1/public/trips/${token}`, { headers: { authorization: `Bearer ${ctx.credential}` } });
  assert.equal(publicTrip.response.status, 200);
  assert.equal(publicTrip.body.trip.destination, null);
  assert.equal(publicTrip.body.trip.latestPositionLabel, null);
  assert.equal("id" in publicTrip.body.trip, false);
  assert.equal("shareToken" in publicTrip.body.trip, false);
  const tripId = absent.body.trip.id;
  await json(ctx, `/api/v1/trips/${tripId}/end`, { method: "POST", headers: management(ctx) });
  const explicitNull = await createTrip(ctx, { origin: null, destination: null });
  assert.equal(explicitNull.response.status, 201);
  assert.equal(explicitNull.body.trip.origin, null);
  assert.equal(explicitNull.body.trip.destination, null);
  assert.equal(explicitNull.body.trip.title, "我的位置共享");
  const restored = await json(ctx, `/api/v1/trips/${explicitNull.body.trip.id}`, { headers: management(ctx) });
  assert.equal(restored.body.trip.shareUrl, explicitNull.body.shareUrl);
});

test("nearby place labels show measured direction and distance, then fall back to an area", () => {
  const place = labelFromRegeo(0.0028, -0.0028, {
    status: "1",
    regeocode: { pois: [{ name: "城东站", location: "0,0" }] }
  });
  assert.match(place ?? "", /^城东站西北 \d+ 米$/);
  const area = labelFromRegeo(23.4, 111.2, {
    status: "1",
    regeocode: { pois: [], addressComponent: { township: "龙圩镇" } }
  });
  assert.equal(area, "龙圩镇附近");
  assert.equal(labelFromRegeo(23.4, 111.2, { status: "0" }), null);
});

test("supports a destination and validates detailed collection settings", async () => {
  const currentTrips = await json(ctx, "/api/v1/trips", { headers: management(ctx) });
  const active = currentTrips.body.trips.find((trip: any) => trip.status === "active");
  await json(ctx, `/api/v1/trips/${active.id}/end`, { method: "POST", headers: management(ctx) });
  const created = await createTrip(ctx, {
    origin: { name: "南宁站", lat: 22.82, lon: 108.32 },
    destination: { name: "滨江站", lat: 23.38, lon: 111.26 },
    sampleIntervalSec: 60,
    uploadIntervalSec: 300,
    mode: "detailed"
  });
  assert.equal(created.response.status, 201);
  assert.equal(created.body.trip.destination.name, "滨江站");
  assert.equal(created.body.trip.title, "南宁站 → 滨江站");
  await json(ctx, `/api/v1/trips/${created.body.trip.id}/end`, { method: "POST", headers: management(ctx) });
  const detailDefaults = await createTrip(ctx, { mode: "detailed" });
  assert.equal(detailDefaults.response.status, 201);
  assert.equal(detailDefaults.body.trip.sampleIntervalSec, 60);
  await json(ctx, `/api/v1/trips/${detailDefaults.body.trip.id}/end`, { method: "POST", headers: management(ctx) });
});

test("map provider is stored per trip, defaults to OSM, and survives on history", async () => {
  const ended = await json(ctx, `/api/v1/trips`, { headers: management(ctx) });
  const active = ended.body.trips.find((trip: any) => trip.status === "active");
  if (active) await json(ctx, `/api/v1/trips/${active.id}/end`, { method: "POST", headers: management(ctx) });
  const osmTrip = await createTrip(ctx);
  assert.equal(osmTrip.response.status, 201);
  assert.equal(osmTrip.body.trip.mapProvider, "OSM");
  await json(ctx, `/api/v1/trips/${osmTrip.body.trip.id}/end`, { method: "POST", headers: management(ctx) });
  const amapTrip = await createTrip(ctx, { origin: { name: "临江镇", lat: 23.40, lon: 111.24 }, mapProvider: "AMAP" });
  assert.equal(amapTrip.response.status, 201);
  assert.equal(amapTrip.body.trip.mapProvider, "AMAP");
  const invalid = await createTrip(ctx, { mapProvider: "GOOGLE" });
  assert.equal(invalid.response.status, 400);
  const list = await json(ctx, "/api/v1/trips", { headers: management(ctx) });
  const byId = new Map(list.body.trips.map((trip: any) => [trip.id, trip.mapProvider]));
  assert.equal(byId.get(osmTrip.body.trip.id), "OSM");
  assert.equal(byId.get(amapTrip.body.trip.id), "AMAP");
  await json(ctx, `/api/v1/trips/${amapTrip.body.trip.id}/end`, { method: "POST", headers: management(ctx) });
});

test("destination can be added, changed, and cleared after a trip starts", async () => {
  const created = await createTrip(ctx, { origin: { name: "临江镇", lat: 23.40, lon: 111.24 } });
  assert.equal(created.response.status, 201);
  const tripId = created.body.trip.id as string;
  const added = await json(ctx, `/api/v1/trips/${tripId}/destination`, {
    method: "PATCH", headers: management(ctx),
    body: JSON.stringify({ destination: { name: "滨江站", lat: 23.38, lon: 111.26 } })
  });
  assert.equal(added.response.status, 200);
  assert.equal(added.body.trip.title, "临江镇 → 滨江站");
  assert.equal(added.body.trip.destination.name, "滨江站");
  const changed = await json(ctx, `/api/v1/trips/${tripId}/destination`, {
    method: "PATCH", headers: management(ctx),
    body: JSON.stringify({ destination: { name: "城东站", lat: 23.42, lon: 111.22 } })
  });
  assert.equal(changed.body.trip.title, "临江镇 → 城东站");
  const cleared = await json(ctx, `/api/v1/trips/${tripId}/destination`, {
    method: "PATCH", headers: management(ctx), body: JSON.stringify({ destination: null })
  });
  assert.equal(cleared.response.status, 200);
  assert.equal(cleared.body.trip.destination, null);
  assert.equal(cleared.body.trip.title, "从临江镇出发");
  const bad = await json(ctx, `/api/v1/trips/${tripId}/destination`, {
    method: "PATCH", headers: management(ctx), body: JSON.stringify({ destination: { name: "", lat: 1, lon: 1 } })
  });
  assert.equal(bad.response.status, 400);
  await json(ctx, `/api/v1/trips/${tripId}/end`, { method: "POST", headers: management(ctx) });
  const afterEnd = await json(ctx, `/api/v1/trips/${tripId}/destination`, {
    method: "PATCH", headers: management(ctx), body: JSON.stringify({ destination: null })
  });
  assert.equal(afterEnd.response.status, 409);
});

test("position upload is idempotent, validates each point, orders by captured time, and permits offline sync after end", async () => {
  const created = await createTrip(ctx);
  assert.equal(created.response.status, 201);
  const tripId = created.body.trip.id as string;
  const capturedAt = new Date(Date.now() - 1000).toISOString();
  const point = {
    id: randomUUID(), lat: 23.45, lon: 111.26, capturedAt, accuracyM: 9,
    speedMps: null, speedAccuracyMps: null, source: "gps", coordinateSystem: "WGS84"
  };
  const ended = await json(ctx, `/api/v1/trips/${tripId}/end`, { method: "POST", headers: management(ctx) });
  assert.equal(ended.body.trip.status, "ended");
  const upload = await json(ctx, `/api/v1/trips/${tripId}/positions`, {
    method: "POST", headers: management(ctx), body: JSON.stringify({ points: [point, { ...point, id: "bad" }] })
  });
  assert.equal(upload.response.status, 200);
  assert.deepEqual(upload.body.acceptedIds, [point.id]);
  assert.equal(upload.body.rejected[0].code, "INVALID_ID");
  const duplicate = await json(ctx, `/api/v1/trips/${tripId}/positions`, {
    method: "POST", headers: management(ctx), body: JSON.stringify({ points: [point] })
  });
  assert.deepEqual(duplicate.body.duplicateIds, [point.id]);
  const latePoint = {
    ...point, id: randomUUID(), capturedAt: new Date(Date.parse(capturedAt) - 1000).toISOString(),
    lat: 23.44
  };
  const lateUpload = await json(ctx, `/api/v1/trips/${tripId}/positions`, {
    method: "POST", headers: management(ctx), body: JSON.stringify({ points: [latePoint] })
  });
  assert.deepEqual(lateUpload.body.acceptedIds, [latePoint.id]);
  const token = new URL(created.body.shareUrl).pathname.split("/").at(-1)!;
  const publicPoints = await json(ctx, `/api/v1/public/trips/${token}/positions`);
  assert.equal(publicPoints.body.points.length, 2);
  assert.equal(publicPoints.body.points[0].id, latePoint.id);
  assert.equal(publicPoints.body.points[1].id, point.id);
  assert.equal(publicPoints.body.points[0].speedMps, null);
  assert.equal(publicPoints.body.points[0].coordinateSystem, "WGS84");
  assert.equal(publicPoints.body.points[0].receivedAt > publicPoints.body.points[0].capturedAt, true);
  ctx.db.prepare("UPDATE positions SET place_label = ? WHERE trip_id = ? AND id = ?")
    .run("龙圩镇西北 430 米", tripId, point.id);
  const publicMetadata = await json(ctx, `/api/v1/public/trips/${token}`);
  assert.equal(publicMetadata.body.trip.latestPositionLabel, "龙圩镇西北 430 米");
  const page = await json(ctx, `/api/v1/public/trips/${token}/positions?after=0&limit=1`);
  assert.equal(page.body.hasMore, true);
  const continuation = await json(ctx, `/api/v1/public/trips/${token}/positions?after=${page.body.nextCursor}&limit=1`);
  assert.equal(continuation.body.points.length, 1);
});

test("management credentials cannot cross device boundaries; revoke and delete hide all public data", async () => {
  const created = await createTrip(ctx);
  const tripId = created.body.trip.id as string;
  const token = new URL(created.body.shareUrl).pathname.split("/").at(-1)!;
  const stranger = await json(ctx, `/api/v1/trips/${tripId}`, { headers: { authorization: `Bearer ${"A".repeat(43)}` } });
  assert.equal(stranger.response.status, 401);
  await json(ctx, `/api/v1/trips/${tripId}/revoke`, { method: "POST", headers: management(ctx) });
  const revoked = await fetch(`${ctx.baseUrl}/api/v1/public/trips/${token}`);
  assert.equal(revoked.status, 404);
  await json(ctx, `/api/v1/trips/${tripId}/end`, { method: "POST", headers: management(ctx) });
  const removed = await fetch(`${ctx.baseUrl}/api/v1/trips/${tripId}`, { method: "DELETE", headers: management(ctx) });
  assert.equal(removed.status, 204);
  const deleted = await fetch(`${ctx.baseUrl}/api/v1/public/trips/${token}`);
  assert.equal(deleted.status, 404);
});

test("AMap security proxy injects only its server-held jscode", async () => {
  const originalFetch = globalThis.fetch;
  let seenUrl: URL | undefined;
  globalThis.fetch = async (input) => {
    seenUrl = new URL(String(input));
    return new Response("{\"status\":\"1\"}", { status: 200, headers: { "content-type": "application/json" } });
  };
  try {
    const proxied = await originalFetch(`${ctx.baseUrl}/_AMapService/v3/geocode/regeo?key=public-js-key&jscode=attacker-value`);
    assert.equal(proxied.status, 200);
    assert.equal(seenUrl?.origin, "https://restapi.amap.com");
    assert.equal(seenUrl?.searchParams.get("jscode"), "server-test-secret");
    assert.equal(seenUrl?.searchParams.get("key"), "public-js-key");
    const directions = await originalFetch(`${ctx.baseUrl}/_AMapService/v3/direction/driving?key=public-js-key`);
    assert.equal(directions.status, 404);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("share links expire independently and data retention deletes expired trips", async () => {
  const created = await createTrip(ctx);
  const token = new URL(created.body.shareUrl).pathname.split("/").at(-1)!;
  const tripId = created.body.trip.id as string;
  ctx.db.prepare("UPDATE trips SET share_expires_at = ? WHERE id = ?").run(new Date(Date.now() - 1000).toISOString(), tripId);
  const expiredLink = await fetch(`${ctx.baseUrl}/api/v1/public/trips/${token}`);
  assert.equal(expiredLink.status, 404);
  ctx.db.prepare("UPDATE trips SET status = 'ended', ended_at = ?, end_reason = 'manual' WHERE id = ?")
    .run(new Date(Date.now() - 100 * 86400_000).toISOString(), tripId);
  const { removeExpiredData } = await import("../domain");
  assert.equal(removeExpiredData(ctx.db, Date.now(), 90), 1);
  assert.equal(ctx.db.prepare("SELECT 1 FROM trips WHERE id = ?").get(tripId), undefined);
});

test("expiry uses its exact deadline, rejects later offline samples, and retention preserves active trips", async () => {
  const created = await createTrip(ctx, { maxShareSeconds: 60 });
  const tripId = created.body.trip.id as string;
  const startedAt = new Date(Date.now() - 120_000);
  ctx.db.prepare("UPDATE trips SET started_at = ? WHERE id = ?").run(startedAt.toISOString(), tripId);
  const { closeExpiredTrips, removeExpiredData } = await import("../domain");
  assert.equal(closeExpiredTrips(ctx.db), 1);
  const expired = ctx.db.prepare("SELECT ended_at, status FROM trips WHERE id = ?").get(tripId) as { ended_at: string; status: string };
  assert.equal(expired.ended_at, new Date(startedAt.getTime() + 60_000).toISOString());
  const tooLate = await json(ctx, `/api/v1/trips/${tripId}/positions`, {
    method: "POST", headers: management(ctx), body: JSON.stringify({ points: [{
      id: randomUUID(), lat: 23.4, lon: 111.2,
      capturedAt: new Date(startedAt.getTime() + 90_000).toISOString(),
      accuracyM: 10, coordinateSystem: "WGS84"
    }] })
  });
  assert.equal(tooLate.body.rejected[0].code, "AFTER_TRIP_END");

  const stillActive = await createTrip(ctx);
  const old = new Date(Date.now() - 100 * 86400_000).toISOString();
  ctx.db.prepare("UPDATE trips SET started_at = ? WHERE id = ?").run(old, stillActive.body.trip.id);
  assert.equal(removeExpiredData(ctx.db, Date.now(), 90), 0);
  assert.ok(ctx.db.prepare("SELECT 1 FROM trips WHERE id = ?").get(stillActive.body.trip.id));
  await json(ctx, `/api/v1/trips/${stillActive.body.trip.id}/end`, { method: "POST", headers: management(ctx) });
});

test("raw impossible jumps remain queryable as outliers and do not become the latest map position", async () => {
  const created = await createTrip(ctx);
  const tripId = created.body.trip.id as string;
  const baseTime = Date.now() - 10_000;
  const points = [
    { id: randomUUID(), lat: 23.4, lon: 111.2, capturedAt: new Date(baseTime).toISOString(), accuracyM: 10, coordinateSystem: "WGS84" },
    { id: randomUUID(), lat: 40.7, lon: -74.0, capturedAt: new Date(baseTime + 60_000).toISOString(), accuracyM: 10, coordinateSystem: "WGS84" }
  ];
  const uploaded = await json(ctx, `/api/v1/trips/${tripId}/positions`, {
    method: "POST", headers: management(ctx), body: JSON.stringify({ points })
  });
  assert.equal(uploaded.body.acceptedIds.length, 2);
  const token = new URL(created.body.shareUrl).pathname.split("/").at(-1)!;
  const metadata = await json(ctx, `/api/v1/public/trips/${token}`);
  assert.equal(metadata.body.trip.latestPosition.lat, 23.4);
  assert.equal(metadata.body.trip.pointCount, 2);
  const history = await json(ctx, `/api/v1/public/trips/${token}/positions`);
  assert.equal(history.body.points.length, 2);
  assert.equal(history.body.points.find((point: any) => point.lat === 40.7).isOutlier, true);
  await json(ctx, `/api/v1/trips/${tripId}/end`, { method: "POST", headers: management(ctx) });
});
