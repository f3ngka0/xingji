import { randomBytes } from "node:crypto";

export interface AppConfig {
  host: string;
  port: number;
  dbPath: string;
  publicBaseUrl: string;
  corsOrigins: Set<string>;
  trustProxyHops: number;
  requireHttps: boolean;
  amapJsSecurityCode: string | null;
  amapWebServiceKey: string | null;
  shareTokenEncryptionKey: Buffer;
  defaultSampleIntervalSec: number;
  defaultMaxShareSeconds: number;
  defaultShareTtlSeconds: number;
  dataRetentionDays: number;
}

function positiveInt(value: string | undefined, fallback: number): number {
  if (value === undefined || value.trim() === "") return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) throw new Error("Invalid positive integer environment setting");
  return parsed;
}

export function readConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const publicBaseUrl = (env.PUBLIC_BASE_URL ?? "http://localhost:3000").replace(/\/+$/, "");
  let parsedBase: URL;
  try {
    parsedBase = new URL(publicBaseUrl);
  } catch {
    throw new Error("PUBLIC_BASE_URL must be an absolute URL");
  }
  if (!["https:", "http:"].includes(parsedBase.protocol)) throw new Error("PUBLIC_BASE_URL must use HTTP or HTTPS");
  let encryptionKey: Buffer;
  const rawEncryptionKey = env.SHARE_TOKEN_ENCRYPTION_KEY?.trim();
  if (rawEncryptionKey) {
    if (!/^[a-fA-F0-9]{64}$/.test(rawEncryptionKey)) throw new Error("SHARE_TOKEN_ENCRYPTION_KEY must be exactly 64 hexadecimal characters");
    encryptionKey = Buffer.from(rawEncryptionKey, "hex");
  } else if (env.NODE_ENV === "production") {
    throw new Error("SHARE_TOKEN_ENCRYPTION_KEY is required in production");
  } else {
    encryptionKey = randomBytes(32);
  }
  const trustProxyHops = Number(env.TRUST_PROXY_HOPS ?? 0);
  if (!Number.isInteger(trustProxyHops) || trustProxyHops < 0 || trustProxyHops > 10) {
    throw new Error("TRUST_PROXY_HOPS must be an integer from 0 to 10");
  }
  const defaultSampleIntervalSec = positiveInt(env.DEFAULT_SAMPLE_INTERVAL_SEC, 300);
  const defaultMaxShareSeconds = positiveInt(env.DEFAULT_MAX_SHARE_SECONDS, 86400);
  const defaultShareTtlSeconds = positiveInt(env.DEFAULT_SHARE_TTL_SECONDS, 30 * 86400);
  const dataRetentionDays = positiveInt(env.DATA_RETENTION_DAYS, 90);
  if (defaultSampleIntervalSec < 60 || defaultSampleIntervalSec > 3600 || defaultSampleIntervalSec % 60 !== 0) {
    throw new Error("DEFAULT_SAMPLE_INTERVAL_SEC must be a whole number of minutes from 60 to 3600");
  }
  if (defaultMaxShareSeconds < 60 || defaultMaxShareSeconds > 7 * 86400) {
    throw new Error("DEFAULT_MAX_SHARE_SECONDS must be from 60 seconds to 7 days");
  }
  if (defaultShareTtlSeconds < 60 || defaultShareTtlSeconds > 365 * 86400) {
    throw new Error("DEFAULT_SHARE_TTL_SECONDS must be from 60 seconds to 365 days");
  }
  if (dataRetentionDays > 3650) throw new Error("DATA_RETENTION_DAYS must not exceed 3650");

  return {
    host: env.HOST ?? "0.0.0.0",
    port: positiveInt(env.PORT, 3000),
    dbPath: env.DB_PATH ?? "./data/trips.sqlite",
    publicBaseUrl,
    corsOrigins: new Set((env.CORS_ORIGINS ?? "").split(",").map((item) => item.trim()).filter(Boolean)),
    trustProxyHops,
    requireHttps: (env.REQUIRE_HTTPS ?? (env.NODE_ENV === "production" ? "true" : "false")).toLowerCase() === "true",
    amapJsSecurityCode: env.AMAP_JS_SECURITY_CODE?.trim() || null,
    amapWebServiceKey: env.AMAP_WEB_SERVICE_KEY?.trim() || null,
    shareTokenEncryptionKey: encryptionKey,
    defaultSampleIntervalSec,
    defaultMaxShareSeconds,
    defaultShareTtlSeconds,
    dataRetentionDays
  };
}
