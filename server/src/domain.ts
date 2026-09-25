import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from "node:crypto";
import type Database from "better-sqlite3";

export const nowIso = (): string => new Date().toISOString();
export const newUuid = (): string => randomUUID();
export const newSecret = (bytes = 32): string => randomBytes(bytes).toString("base64url");
export const sha256 = (value: string): string => createHash("sha256").update(value, "utf8").digest("hex");

export function encryptSecret(value: string, key: Buffer): string {
  const nonce = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  const encrypted = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
  return [nonce.toString("hex"), cipher.getAuthTag().toString("hex"), encrypted.toString("hex")].join(":");
}

export function decryptSecret(value: string, key: Buffer): string {
  const [nonceHex, tagHex, encryptedHex] = value.split(":");
  if (!nonceHex || !tagHex || !encryptedHex) throw new Error("Invalid encrypted secret");
  const decipher = createDecipheriv("aes-256-gcm", key, Buffer.from(nonceHex, "hex"));
  decipher.setAuthTag(Buffer.from(tagHex, "hex"));
  return Buffer.concat([decipher.update(Buffer.from(encryptedHex, "hex")), decipher.final()]).toString("utf8");
}

export interface TripRow {
  id: string;
  device_id: string;
  title: string;
  origin_name: string | null;
  origin_lat: number | null;
  origin_lon: number | null;
  destination_name: string | null;
  destination_lat: number | null;
  destination_lon: number | null;
  status: "active" | "ended";
  started_at: string;
  ended_at: string | null;
  end_reason: "manual" | "expired" | null;
  sample_interval_sec: number;
  upload_interval_sec: number;
  mode: "standard" | "detailed";
  max_share_seconds: number;
  share_expires_at: string;
  share_revoked_at: string | null;
  share_token_ciphertext: string | null;
}

export interface PositionRow {
  sequence: number;
  id: string;
  trip_id: string;
  lat: number;
  lon: number;
  captured_at: string;
  received_at: string;
  accuracy_m: number;
  speed_mps: number | null;
  speed_accuracy_mps: number | null;
  source: string | null;
  coordinate_system: "WGS84";
  is_outlier: number;
  place_label: string | null;
  place_label_checked_at: string | null;
}

export function tripToApi(db: Database.Database, row: TripRow) {
  const stats = db.prepare(`SELECT COUNT(*) AS count, MAX(captured_at) AS latest
    FROM positions WHERE trip_id = ? AND is_outlier = 0`).get(row.id) as { count: number; latest: string | null };
  const pointCount = db.prepare("SELECT COUNT(*) AS count FROM positions WHERE trip_id = ?").get(row.id) as { count: number };
  const origin = row.origin_name === null ? null : { name: row.origin_name, lat: row.origin_lat!, lon: row.origin_lon! };
  const destination = row.destination_name === null ? null : {
    name: row.destination_name,
    lat: row.destination_lat!,
    lon: row.destination_lon!
  };
  return {
    id: row.id,
    title: row.title,
    origin,
    destination,
    status: row.status,
    startedAt: row.started_at,
    endedAt: row.ended_at,
    endReason: row.end_reason,
    sampleIntervalSec: row.sample_interval_sec,
    uploadIntervalSec: row.upload_interval_sec,
    mode: row.mode,
    maxShareSeconds: row.max_share_seconds,
    shareExpiresAt: row.share_expires_at,
    latestPositionAt: stats.latest,
    pointCount: pointCount.count,
    shareRevokedAt: row.share_revoked_at
  };
}

export function publicPosition(row: PositionRow) {
  return {
    id: row.id,
    sequence: row.sequence,
    lat: row.lat,
    lon: row.lon,
    capturedAt: row.captured_at,
    receivedAt: row.received_at,
    accuracyM: row.accuracy_m,
    speedMps: row.speed_mps,
    speedAccuracyMps: row.speed_accuracy_mps,
    source: row.source,
    coordinateSystem: row.coordinate_system,
    isOutlier: row.is_outlier === 1
  };
}

export function publicTrip(db: Database.Database, row: TripRow) {
  const trip = tripToApi(db, row);
  const latest = db.prepare(`SELECT * FROM positions WHERE trip_id = ? AND is_outlier = 0
    ORDER BY captured_at DESC, id DESC LIMIT 1`).get(row.id) as PositionRow | undefined;
  return {
    title: trip.title,
    origin: trip.origin,
    destination: trip.destination,
    status: trip.status,
    startedAt: trip.startedAt,
    endedAt: trip.endedAt,
    sampleIntervalSec: trip.sampleIntervalSec,
    uploadIntervalSec: trip.uploadIntervalSec,
    mode: trip.mode,
    latestPositionAt: trip.latestPositionAt,
    latestPositionLabel: latest?.place_label ?? null,
    pointCount: trip.pointCount,
    latestPosition: latest ? publicPosition(latest) : null
  };
}

export function closeExpiredTrips(db: Database.Database, now = Date.now()): number {
  const active = db.prepare("SELECT id, started_at, max_share_seconds FROM trips WHERE status = 'active'").all() as Array<{
    id: string; started_at: string; max_share_seconds: number;
  }>;
  const end = db.prepare(`UPDATE trips SET status = 'ended', ended_at = ?, end_reason = 'expired'
    WHERE id = ? AND status = 'active'`);
  let count = 0;
  for (const row of active) {
    const expiryTime = Date.parse(row.started_at) + row.max_share_seconds * 1000;
    if (expiryTime <= now) count += end.run(new Date(expiryTime).toISOString(), row.id).changes;
  }
  return count;
}

export function removeExpiredData(db: Database.Database, now = Date.now(), retentionDays = 90): number {
  const cutoff = new Date(now - retentionDays * 86400_000).toISOString();
  return db.prepare(`DELETE FROM trips WHERE status = 'ended' AND COALESCE(ended_at, started_at) < ?`).run(cutoff).changes;
}

export function markOutliers(db: Database.Database, tripId: string): void {
  const rows = db.prepare(`SELECT sequence, lat, lon, captured_at FROM positions
    WHERE trip_id = ? ORDER BY captured_at ASC, id ASC`).all(tripId) as Array<{
      sequence: number; lat: number; lon: number; captured_at: string;
    }>;
  const update = db.prepare("UPDATE positions SET is_outlier = ? WHERE sequence = ?");
  let anchor: (typeof rows)[number] | undefined;
  for (const point of rows) {
    let outlier = false;
    if (anchor) {
      const elapsedSeconds = Math.max(1, (Date.parse(point.captured_at) - Date.parse(anchor.captured_at)) / 1000);
      const distanceMeters = haversineMeters(anchor.lat, anchor.lon, point.lat, point.lon);
      // Keep genuine fast rail movement while flagging impossible single-point jumps.
      outlier = distanceMeters >= 25_000 && distanceMeters / elapsedSeconds > 125;
    }
    update.run(outlier ? 1 : 0, point.sequence);
    if (!outlier) anchor = point;
  }
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = (degree: number) => degree * Math.PI / 180;
  const dLat = rad(lat2 - lat1);
  const dLon = rad(lon2 - lon1);
  const value = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(Math.min(1, value)));
}
