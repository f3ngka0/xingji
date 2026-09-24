import express, { type ErrorRequestHandler, type Request, type RequestHandler, type Response } from "express";
import helmet from "helmet";
import { rateLimit } from "express-rate-limit";
import type Database from "better-sqlite3";
import { z } from "zod";
import type { AppConfig } from "./config";
import { closeExpiredTrips, decryptSecret, encryptSecret, markOutliers, newSecret, newUuid, nowIso, publicPosition, publicTrip, sha256, tripToApi, type TripRow } from "./domain";
import { pointErrorCode, pointInputSchema, createTripSchema, settingsSchema, uuidSchema } from "./validation";

const error = (res: Response, status: number, code: string, message: string) =>
  res.status(status).json({ error: { code, message } });

const allowedAmapPaths = new Set([
  "/v3/geocode/geo",
  "/v3/geocode/regeo",
  "/v3/assistant/inputtips",
  "/v3/place/text",
  "/v3/place/around",
  "/v3/place/detail",
  "/v3/place/polygon",
  "/v3/place/children"
]);

function safeIpRateLimit(windowMs: number, limit: number) {
  return rateLimit({
    windowMs,
    limit,
    standardHeaders: true,
    legacyHeaders: false,
    handler: (_req, res) => error(res, 429, "RATE_LIMITED", "请求过于频繁，请稍后再试。")
  });
}

export function createApp(db: Database.Database, config: AppConfig): express.Express {
  const app = express();
  app.disable("x-powered-by");
  app.set("trust proxy", config.trustProxyHops);
  app.use(helmet({
    contentSecurityPolicy: false,
    crossOriginResourcePolicy: { policy: "same-origin" },
    referrerPolicy: { policy: "no-referrer" }
  }));
  app.use((req, res, next) => {
    res.setHeader("Cache-Control", "no-store");
    if (req.path.startsWith("/api/v1/public/")) {
      res.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
      res.setHeader("Referrer-Policy", "no-referrer");
    }
    const origin = req.header("Origin");
    if (origin) {
      if (!config.corsOrigins.has(origin)) return error(res, 403, "ORIGIN_NOT_ALLOWED", "此来源未获准访问。" );
      res.setHeader("Access-Control-Allow-Origin", origin);
      res.setHeader("Vary", "Origin");
      res.setHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
      res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
      res.setHeader("Access-Control-Max-Age", "600");
    }
    if (req.method === "OPTIONS") return res.status(204).end();
    next();
  });
  app.use((req, res, next) => {
    if (config.requireHttps && !req.secure && req.path !== "/healthz") {
      return error(res, 426, "HTTPS_REQUIRED", "请通过 HTTPS 访问此服务。" );
    }
    next();
  });

  app.get("/healthz", (_req, res) => {
    try {
      db.prepare("SELECT 1").get();
      res.json({ status: "ok" });
    } catch {
      error(res, 503, "UNAVAILABLE", "服务暂不可用。" );
    }
  });

  const amapLimiter = safeIpRateLimit(60_000, 120);
  app.use("/_AMapService", amapLimiter, async (req, res) => {
    if (req.method !== "GET") return error(res, 405, "METHOD_NOT_ALLOWED", "只支持 GET 请求。" );
    if (!config.amapJsSecurityCode) return error(res, 503, "MAP_PROXY_NOT_CONFIGURED", "地图安全代理尚未配置。" );
    const upstreamPath = req.path;
    if (!allowedAmapPaths.has(upstreamPath)) {
      return error(res, 404, "NOT_FOUND", "请求不存在。" );
    }
    const target = new URL(upstreamPath, "https://restapi.amap.com");
    for (const [key, value] of Object.entries(req.query)) {
      if (typeof value === "string" && key !== "jscode") target.searchParams.set(key, value);
    }
    target.searchParams.set("jscode", config.amapJsSecurityCode);
    try {
      const upstream = await fetch(target, { method: "GET", redirect: "error", signal: AbortSignal.timeout(10_000) });
      res.status(upstream.status);
      const contentType = upstream.headers.get("content-type");
      if (contentType) res.setHeader("Content-Type", contentType);
      const cacheControl = upstream.headers.get("cache-control");
      if (cacheControl) res.setHeader("Cache-Control", cacheControl);
      const body = Buffer.from(await upstream.arrayBuffer());
      res.send(body);
    } catch {
      error(res, 502, "MAP_SERVICE_UNAVAILABLE", "地图服务暂不可用。" );
    }
  });

  const publicLimiter = safeIpRateLimit(60_000, 240);
  const apiLimiter = safeIpRateLimit(60_000, 180);
  const registrationLimiter = safeIpRateLimit(60 * 60_000, 30);
  const uploadLimiter = safeIpRateLimit(60_000, 60);
  app.use("/api", apiLimiter);
  app.use(express.json({ limit: "512kb", strict: true }));

  const requireDevice: RequestHandler = (req, res, next) => {
    const authorization = req.header("Authorization") ?? "";
    const match = /^Bearer ([A-Za-z0-9_-]{40,64})$/.exec(authorization);
    if (!match) return error(res, 401, "UNAUTHORIZED", "需要有效的设备凭证。" );
    const row = db.prepare("SELECT id FROM devices WHERE credential_hash = ?").get(sha256(match[1]!)) as { id: string } | undefined;
    if (!row) return error(res, 401, "UNAUTHORIZED", "需要有效的设备凭证。" );
    res.locals.deviceId = row.id;
    next();
  };

  app.post("/api/v1/devices", registrationLimiter, (req, res) => {
    const parsed = z.object({ installationId: uuidSchema }).strict().safeParse(req.body);
    if (!parsed.success) return error(res, 400, "INVALID_REQUEST", "安装标识必须是 UUID。" );
    const existing = db.prepare("SELECT id FROM devices WHERE installation_id = ?").get(parsed.data.installationId) as { id: string } | undefined;
    if (existing) return res.status(200).json({ deviceId: existing.id });
    const deviceId = newUuid();
    const credential = newSecret();
    try {
      db.prepare("INSERT INTO devices (id, installation_id, credential_hash, created_at) VALUES (?, ?, ?, ?)")
        .run(deviceId, parsed.data.installationId, sha256(credential), nowIso());
    } catch {
      // A concurrent first registration may win the installation ID uniqueness race.
      const raced = db.prepare("SELECT id FROM devices WHERE installation_id = ?").get(parsed.data.installationId) as { id: string } | undefined;
      if (raced) return res.status(200).json({ deviceId: raced.id });
      return error(res, 500, "INTERNAL_ERROR", "无法注册此设备。" );
    }
    return res.status(201).json({ deviceId, credential });
  });

  app.post("/api/v1/trips", requireDevice, (req, res) => {
    const parsed = createTripSchema.safeParse(req.body);
    if (!parsed.success) return error(res, 400, "INVALID_REQUEST", "行程参数无效。" );
    const origin = parsed.data.origin ?? null;
    const destination = parsed.data.destination ?? null;
    const mode = parsed.data.mode ?? "standard";
    const sampleIntervalSec = parsed.data.sampleIntervalSec ?? (mode === "detailed" ? 60 : config.defaultSampleIntervalSec);
    const uploadIntervalSec = parsed.data.uploadIntervalSec ?? sampleIntervalSec;
    const maxShareSeconds = parsed.data.maxShareSeconds ?? config.defaultMaxShareSeconds;
    const settingsError = validateSettings(sampleIntervalSec, uploadIntervalSec, mode, maxShareSeconds);
    if (settingsError) return error(res, 400, "INVALID_SETTINGS", settingsError);
    const active = db.prepare("SELECT id FROM trips WHERE device_id = ? AND status = 'active'")
      .get(res.locals.deviceId as string) as { id: string } | undefined;
    if (active) return error(res, 409, "ACTIVE_TRIP_EXISTS", "请先结束当前行程，再创建新行程。" );

    const startedAt = nowIso();
    const shareExpiresAt = new Date(Date.now() + config.defaultShareTtlSeconds * 1000).toISOString();
    const token = newSecret();
    const tripId = newUuid();
    const title = origin && destination
      ? `${origin.name} → ${destination.name}`
      : origin ? `从${origin.name}出发的行程` : "我的位置共享";
    try {
      db.prepare(`INSERT INTO trips (
        id, device_id, title, origin_name, origin_lat, origin_lon,
        destination_name, destination_lat, destination_lon, status, started_at,
        sample_interval_sec, upload_interval_sec, mode, max_share_seconds,
        share_token_hash, share_expires_at, share_token_ciphertext
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?, ?, ?, ?, ?)`)
        .run(
          tripId, res.locals.deviceId as string, title,
          origin?.name ?? null, origin?.lat ?? null, origin?.lon ?? null,
          destination?.name ?? null, destination?.lat ?? null, destination?.lon ?? null,
          startedAt, sampleIntervalSec, uploadIntervalSec, mode, maxShareSeconds, sha256(token), shareExpiresAt,
          encryptSecret(token, config.shareTokenEncryptionKey)
        );
    } catch {
      return error(res, 409, "ACTIVE_TRIP_EXISTS", "请先结束当前行程，再创建新行程。" );
    }
    const row = db.prepare("SELECT * FROM trips WHERE id = ?").get(tripId) as Parameters<typeof tripToApi>[1];
    const shareUrl = `${config.publicBaseUrl}/trip/${token}`;
    return res.status(201).json({ trip: { ...tripToApi(db, row), shareUrl }, shareUrl });
  });

  app.get("/api/v1/trips", requireDevice, (req, res) => {
    closeExpiredTrips(db);
    const rows = db.prepare("SELECT * FROM trips WHERE device_id = ? ORDER BY started_at DESC LIMIT 500")
      .all(res.locals.deviceId as string) as Parameters<typeof tripToApi>[1][];
    res.json({ trips: rows.map((row) => managementTripToApi(db, row, config)) });
  });

  app.get("/api/v1/trips/:id", requireDevice, (req, res) => {
    const id = routeParam(req, "id");
    if (!uuidSchema.safeParse(id).success) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    closeExpiredTrips(db);
    const row = getOwnedTrip(db, id, res.locals.deviceId as string);
    if (!row) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    res.json({ trip: managementTripToApi(db, row, config) });
  });

  app.patch("/api/v1/trips/:id/settings", requireDevice, (req, res) => {
    const id = routeParam(req, "id");
    if (!uuidSchema.safeParse(id).success) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    const parsed = settingsSchema.safeParse(req.body);
    if (!parsed.success) return error(res, 400, "INVALID_REQUEST", "设置参数无效。" );
    const row = getOwnedTrip(db, id, res.locals.deviceId as string);
    if (!row) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    if (row.status !== "active") return error(res, 409, "TRIP_ENDED", "已结束的行程不能修改设置。" );
    const values = {
      sample: parsed.data.sampleIntervalSec ?? (parsed.data.mode === "detailed" && row.mode !== "detailed" ? 60 : row.sample_interval_sec),
      upload: parsed.data.uploadIntervalSec ?? row.upload_interval_sec,
      mode: parsed.data.mode ?? row.mode,
      max: parsed.data.maxShareSeconds ?? row.max_share_seconds
    };
    const message = validateSettings(values.sample, values.upload, values.mode, values.max);
    if (message) return error(res, 400, "INVALID_SETTINGS", message);
    db.prepare(`UPDATE trips SET sample_interval_sec = ?, upload_interval_sec = ?, mode = ?, max_share_seconds = ?
      WHERE id = ?`).run(values.sample, values.upload, values.mode, values.max, row.id);
    closeExpiredTrips(db);
    const updated = db.prepare("SELECT * FROM trips WHERE id = ?").get(row.id) as Parameters<typeof tripToApi>[1];
    res.json({ trip: managementTripToApi(db, updated, config) });
  });

  app.post("/api/v1/trips/:id/end", requireDevice, (req, res) => {
    const id = routeParam(req, "id");
    if (!uuidSchema.safeParse(id).success) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    closeExpiredTrips(db);
    const row = getOwnedTrip(db, id, res.locals.deviceId as string);
    if (!row) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    if (row.status === "active") {
      db.prepare("UPDATE trips SET status = 'ended', ended_at = ?, end_reason = 'manual' WHERE id = ? AND status = 'active'")
        .run(nowIso(), row.id);
    }
    const updated = db.prepare("SELECT * FROM trips WHERE id = ?").get(row.id) as Parameters<typeof tripToApi>[1];
    res.json({ trip: managementTripToApi(db, updated, config) });
  });

  app.post("/api/v1/trips/:id/revoke", requireDevice, (req, res) => {
    const id = routeParam(req, "id");
    if (!uuidSchema.safeParse(id).success) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    const row = getOwnedTrip(db, id, res.locals.deviceId as string);
    if (!row) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    if (!row.share_revoked_at) db.prepare("UPDATE trips SET share_revoked_at = ? WHERE id = ?").run(nowIso(), row.id);
    const updated = db.prepare("SELECT * FROM trips WHERE id = ?").get(row.id) as Parameters<typeof tripToApi>[1];
    res.json({ trip: managementTripToApi(db, updated, config) });
  });

  app.delete("/api/v1/trips/:id", requireDevice, (req, res) => {
    const id = routeParam(req, "id");
    if (!uuidSchema.safeParse(id).success) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    const result = db.prepare("DELETE FROM trips WHERE id = ? AND device_id = ?")
      .run(id, res.locals.deviceId as string);
    if (result.changes === 0) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    res.status(204).end();
  });

  app.post("/api/v1/trips/:id/positions", uploadLimiter, requireDevice, (req, res) => {
    const id = routeParam(req, "id");
    if (!uuidSchema.safeParse(id).success) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    closeExpiredTrips(db);
    const row = getOwnedTrip(db, id, res.locals.deviceId as string);
    if (!row) return error(res, 404, "NOT_FOUND", "未找到此行程。" );
    const body = z.object({ points: z.array(z.unknown()).min(1).max(100) }).strict().safeParse(req.body);
    if (!body.success) return error(res, 400, "INVALID_REQUEST", "每批必须包含 1 到 100 个位置点。" );
    const acceptedIds: string[] = [];
    const duplicateIds: string[] = [];
    const rejected: Array<{ id: string; code: string }> = [];
    const insert = db.prepare(`INSERT INTO positions (
      trip_id, id, lat, lon, captured_at, received_at, accuracy_m,
      speed_mps, speed_accuracy_mps, source, coordinate_system
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WGS84')`);
    const exists = db.prepare("SELECT 1 FROM positions WHERE trip_id = ? AND id = ?");
    const receivedAt = nowIso();

    const transaction = db.transaction(() => {
      for (const value of body.data.points) {
        const rawId = value && typeof value === "object" && "id" in value && typeof (value as { id?: unknown }).id === "string"
          ? String((value as { id: string }).id).slice(0, 80)
          : "";
        const point = pointInputSchema.safeParse(value);
        if (!point.success) {
          rejected.push({ id: rawId, code: pointErrorCode(point.error) });
          continue;
        }
        const capturedMs = Date.parse(point.data.capturedAt);
        if (capturedMs > Date.now() + 5 * 60_000) {
          rejected.push({ id: point.data.id, code: "FUTURE_TIMESTAMP" });
          continue;
        }
        if (capturedMs < Date.parse(row.started_at) - 5 * 60_000) {
          rejected.push({ id: point.data.id, code: "BEFORE_TRIP_START" });
          continue;
        }
        if (row.ended_at && capturedMs > Date.parse(row.ended_at)) {
          rejected.push({ id: point.data.id, code: "AFTER_TRIP_END" });
          continue;
        }
        if (exists.get(row.id, point.data.id)) {
          duplicateIds.push(point.data.id);
          continue;
        }
        insert.run(
          row.id, point.data.id, point.data.lat, point.data.lon, new Date(capturedMs).toISOString(), receivedAt,
          point.data.accuracyM, point.data.speedMps, point.data.speedAccuracyMps, point.data.source
        );
        acceptedIds.push(point.data.id);
      }
      if (acceptedIds.length) markOutliers(db, row.id);
    });
    try {
      transaction();
    } catch {
      return error(res, 500, "INTERNAL_ERROR", "无法保存位置数据。" );
    }
    return res.json({ acceptedIds, duplicateIds, rejected });
  });

  app.get("/api/v1/public/trips/:token", publicLimiter, (req, res) => {
    const token = routeParam(req, "token");
    const row = getPublicTrip(db, token);
    if (!row) return error(res, 404, "NOT_FOUND", "此分享链接无效或已失效。" );
    closeExpiredTrips(db);
    const refreshed = getPublicTrip(db, token);
    if (!refreshed) return error(res, 404, "NOT_FOUND", "此分享链接无效或已失效。" );
    res.json({ trip: publicTrip(db, refreshed) });
  });

  app.get("/api/v1/public/trips/:token/positions", publicLimiter, (req, res) => {
    const token = routeParam(req, "token");
    const row = getPublicTrip(db, token);
    if (!row) return error(res, 404, "NOT_FOUND", "此分享链接无效或已失效。" );
    closeExpiredTrips(db);
    const refreshed = getPublicTrip(db, token);
    if (!refreshed) return error(res, 404, "NOT_FOUND", "此分享链接无效或已失效。" );
    const afterValue = req.query.after ?? "0";
    const limitValue = req.query.limit ?? "500";
    if (typeof afterValue !== "string" || !/^\d+$/.test(afterValue) || typeof limitValue !== "string" || !/^\d+$/.test(limitValue)) {
      return error(res, 400, "INVALID_CURSOR", "分页参数无效。" );
    }
    const after = Number(afterValue);
    const requestedLimit = Number(limitValue);
    if (!Number.isSafeInteger(after) || !Number.isSafeInteger(requestedLimit) || requestedLimit < 1) {
      return error(res, 400, "INVALID_CURSOR", "分页参数无效。" );
    }
    const limit = Math.min(requestedLimit, 500);
    const rows = db.prepare(`SELECT * FROM positions WHERE trip_id = ? AND sequence > ?
      ORDER BY sequence ASC LIMIT ?`).all(refreshed.id, after, limit) as Parameters<typeof publicPosition>[0][];
    const hasMore = rows.length === limit && Boolean(db.prepare(`SELECT 1 FROM positions
      WHERE trip_id = ? AND sequence > ? LIMIT 1`).get(refreshed.id, rows[rows.length - 1]!.sequence));
    const points = rows.map(publicPosition).sort((a, b) => a.capturedAt.localeCompare(b.capturedAt) || a.id.localeCompare(b.id));
    res.json({
      points,
      nextCursor: hasMore && rows.length ? rows[rows.length - 1]!.sequence : null,
      hasMore
    });
  });

  app.use((_req, res) => error(res, 404, "NOT_FOUND", "请求不存在。"));
  const errorHandler: ErrorRequestHandler = (err: unknown, _req, res, _next) => {
    if (err && typeof err === "object" && "type" in err && (err as { type?: string }).type === "entity.parse.failed") {
      return error(res, 400, "INVALID_JSON", "请求内容不是有效的 JSON。" );
    }
    if (err && typeof err === "object" && "type" in err && (err as { type?: string }).type === "entity.too.large") {
      return error(res, 413, "REQUEST_TOO_LARGE", "请求内容过大。" );
    }
    return error(res, 500, "INTERNAL_ERROR", "服务暂时无法处理此请求。" );
  };
  app.use(errorHandler);
  return app;
}

function getOwnedTrip(db: Database.Database, tripId: string, deviceId: string) {
  return db.prepare("SELECT * FROM trips WHERE id = ? AND device_id = ?")
    .get(tripId, deviceId) as TripRow | undefined;
}

function routeParam(req: Request, name: string): string {
  const value = req.params[name];
  return typeof value === "string" ? value : "";
}

function getTripRow(db: Database.Database, tripId: string) {
  return db.prepare("SELECT * FROM trips WHERE id = ?").get(tripId) as TripRow | undefined;
}

function getPublicTrip(db: Database.Database, token: string) {
  if (token.length < 32 || token.length > 64) return undefined;
  const row = db.prepare("SELECT * FROM trips WHERE share_token_hash = ?")
    .get(sha256(token)) as ReturnType<typeof getTripRow>;
  if (!row || row.share_revoked_at || Date.parse(row.share_expires_at) <= Date.now()) return undefined;
  return row;
}

function managementTripToApi(db: Database.Database, row: TripRow, config: AppConfig) {
  const trip = tripToApi(db, row);
  if (!row.share_revoked_at && Date.parse(row.share_expires_at) > Date.now() && row.share_token_ciphertext) {
    try {
      return { ...trip, shareUrl: `${config.publicBaseUrl}/trip/${decryptSecret(row.share_token_ciphertext, config.shareTokenEncryptionKey)}` };
    } catch {
      return trip;
    }
  }
  return trip;
}

function validateSettings(sample: number, upload: number, mode: string, maxShareSeconds: number): string | null {
  if (!Number.isInteger(sample) || sample < 60 || sample > 3600 || sample % 60 !== 0) {
    return "采样间隔须为 1 到 60 分钟之间的任意整分钟。";
  }
  if (!Number.isInteger(upload) || upload < 60 || upload > 3600 || upload % 60 !== 0) {
    return "上传间隔须为 1 到 60 分钟之间的任意整分钟。";
  }
  if (upload < sample) return "上传间隔不能短于位置采样间隔。";
  if (mode === "detailed" && sample !== 60) return "详细轨迹模式每 1 分钟采样一次。";
  if (!Number.isInteger(maxShareSeconds) || maxShareSeconds < 60 || maxShareSeconds > 7 * 86400) {
    return "最长共享时间须在 1 分钟到 7 天之间。";
  }
  return null;
}
