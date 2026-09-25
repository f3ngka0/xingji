import type Database from "better-sqlite3";
import type { AppConfig } from "./config";
import type { PositionRow } from "./domain";

const minimumLookupIntervalMs = 2 * 60_000;
const inFlight = new WeakMap<Database.Database, Set<string>>();

type AmapPlace = { name?: unknown; location?: unknown };
type AmapRegeo = {
  status?: string;
  regeocode?: {
    formatted_address?: unknown;
    pois?: unknown;
    addressComponent?: { township?: unknown; district?: unknown; city?: unknown };
  };
};

/** Resolve a label for the latest measured point. It never delays location upload or public reads. */
export function scheduleLatestPlaceLabel(db: Database.Database, config: AppConfig, tripId: string): void {
  if (!config.amapWebServiceKey) return;
  let running = inFlight.get(db);
  if (!running) {
    running = new Set();
    inFlight.set(db, running);
  }
  if (running.has(tripId)) return;
  const latest = db.prepare(`SELECT * FROM positions WHERE trip_id = ? AND is_outlier = 0
    ORDER BY captured_at DESC, id DESC LIMIT 1`).get(tripId) as PositionRow | undefined;
  if (!latest || latest.place_label) return;
  const prior = db.prepare(`SELECT MAX(place_label_checked_at) AS checked_at FROM positions WHERE trip_id = ?`)
    .get(tripId) as { checked_at: string | null };
  if (prior.checked_at && Date.now() - Date.parse(prior.checked_at) < minimumLookupIntervalMs) return;

  db.prepare("UPDATE positions SET place_label_checked_at = ? WHERE sequence = ?")
    .run(new Date().toISOString(), latest.sequence);
  running.add(tripId);
  void reverseGeocode(latest.lat, latest.lon, config.amapWebServiceKey)
    .then((label) => {
      if (label && db.open) db.prepare("UPDATE positions SET place_label = ? WHERE sequence = ?").run(label, latest.sequence);
    })
    .catch(() => { /* Lookup is optional; the checked timestamp limits retries. */ })
    .finally(() => running!.delete(tripId));
}

async function reverseGeocode(lat: number, lon: number, key: string): Promise<string | null> {
  const url = new URL("https://restapi.amap.com/v3/geocode/regeo");
  url.searchParams.set("key", key);
  url.searchParams.set("location", `${lon},${lat}`);
  url.searchParams.set("coordsys", "gps");
  url.searchParams.set("extensions", "all");
  url.searchParams.set("radius", "2000");
  const response = await fetch(url, { signal: AbortSignal.timeout(4000), redirect: "error" });
  if (!response.ok) return null;
  const data = await response.json() as AmapRegeo;
  return labelFromRegeo(lat, lon, data);
}

export function labelFromRegeo(lat: number, lon: number, data: AmapRegeo): string | null {
  if (data.status !== "1" || !data.regeocode) return null;
  const point = wgs84ToGcj02(lat, lon);
  const pois = Array.isArray(data.regeocode.pois) ? data.regeocode.pois as AmapPlace[] : [];
  const candidates = pois.flatMap((poi) => {
    const name = cleanLabel(poi.name);
    const location = typeof poi.location === "string" ? poi.location.split(",").map(Number) : [];
    if (!name || location.length !== 2 || !location.every(Number.isFinite)) return [];
    const distance = haversineMeters(point.lat, point.lon, location[1]!, location[0]!);
    return distance <= 2000 ? [{ name, lat: location[1]!, lon: location[0]!, distance }] : [];
  }).sort((a, b) => a.distance - b.distance);
  if (candidates[0]) {
    const nearest = candidates[0];
    if (nearest.distance < 50) return nearest.name;
    const direction = directionFrom(nearest.lat, nearest.lon, point.lat, point.lon);
    const roundedDistance = Math.max(10, Math.round(nearest.distance / 10) * 10);
    return `${nearest.name}${direction} ${roundedDistance} 米`;
  }

  const component = data.regeocode.addressComponent;
  const area = cleanLabel(component?.township) ?? cleanLabel(component?.district)
    ?? cleanLabel(component?.city);
  if (area) return `${area}附近`;
  const address = cleanLabel(data.regeocode.formatted_address);
  return address ? `${address}附近` : null;
}

function cleanLabel(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const label = value.trim().replace(/[\r\n\t]/g, " ").slice(0, 80);
  return label || null;
}

function directionFrom(fromLat: number, fromLon: number, toLat: number, toLon: number): string {
  const rad = Math.PI / 180;
  const y = Math.sin((toLon - fromLon) * rad) * Math.cos(toLat * rad);
  const x = Math.cos(fromLat * rad) * Math.sin(toLat * rad)
    - Math.sin(fromLat * rad) * Math.cos(toLat * rad) * Math.cos((toLon - fromLon) * rad);
  const bearing = (Math.atan2(y, x) / rad + 360) % 360;
  return ["北", "东北", "东", "东南", "南", "西南", "西", "西北"][Math.round(bearing / 45) % 8]!;
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(Math.min(1, a)));
}

function wgs84ToGcj02(lat: number, lon: number): { lat: number; lon: number } {
  if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return { lat, lon };
  const x = lon - 105;
  const y = lat - 35;
  let dLat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  dLat += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
  dLat += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
  dLat += (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
  let dLon = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  dLon += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
  dLon += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
  dLon += (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
  const latitudeRad = lat / 180 * Math.PI;
  const magic = 1 - 0.00669342162296594323 * Math.sin(latitudeRad) ** 2;
  const sqrtMagic = Math.sqrt(magic);
  dLat = dLat * 180 / ((6335552.717000426 / (magic * sqrtMagic)) * Math.PI);
  dLon = dLon * 180 / ((6378245 / sqrtMagic * Math.cos(latitudeRad)) * Math.PI);
  return { lat: lat + dLat, lon: lon + dLon };
}
