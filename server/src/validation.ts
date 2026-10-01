import { z } from "zod";

export const uuidSchema = z.string().uuid();

export const placeSchema = z.object({
  name: z.string().trim().min(1).max(160),
  lat: z.number().finite().min(-90).max(90),
  lon: z.number().finite().min(-180).max(180)
}).strict();

const intervalSchema = z.number().int().min(60).max(3600).refine((value) => value % 60 === 0, "Interval must be a whole number of minutes");
const maxShareSchema = z.number().int().min(60).max(7 * 86400);

export const mapProviderSchema = z.enum(["OSM", "AMAP"]);

export const createTripSchema = z.object({
  origin: placeSchema.nullable().optional(),
  destination: placeSchema.nullable().optional(),
  sampleIntervalSec: intervalSchema.optional(),
  uploadIntervalSec: intervalSchema.optional(),
  mode: z.enum(["standard", "detailed"]).optional(),
  maxShareSeconds: maxShareSchema.optional(),
  mapProvider: mapProviderSchema.optional()
}).strict();

export const destinationSchema = z.object({
  destination: placeSchema.nullable()
}).strict();

export const settingsSchema = z.object({
  sampleIntervalSec: intervalSchema.optional(),
  uploadIntervalSec: intervalSchema.optional(),
  mode: z.enum(["standard", "detailed"]).optional(),
  maxShareSeconds: maxShareSchema.optional()
}).strict().refine((value) => Object.keys(value).length > 0, "At least one setting is required");

export const pointInputSchema = z.object({
  id: uuidSchema,
  lat: z.number().finite().min(-90).max(90),
  lon: z.number().finite().min(-180).max(180),
  capturedAt: z.string().datetime({ offset: true }),
  accuracyM: z.number().finite().min(0).max(100_000),
  speedMps: z.number().finite().min(0).max(1000).nullable().optional().default(null),
  speedAccuracyMps: z.number().finite().min(0).max(1000).nullable().optional().default(null),
  source: z.string().trim().max(50).nullable().optional().default(null),
  coordinateSystem: z.literal("WGS84")
}).strict();

export function pointErrorCode(error: z.ZodError): string {
  const path = error.issues[0]?.path[0];
  if (path === "id") return "INVALID_ID";
  if (path === "lat" || path === "lon") return "INVALID_COORDINATE";
  if (path === "capturedAt") return "INVALID_CAPTURED_AT";
  if (path === "coordinateSystem") return "UNSUPPORTED_COORDINATE_SYSTEM";
  if (path === "accuracyM") return "INVALID_ACCURACY";
  return "INVALID_POINT";
}
