import { z } from 'zod';

export const PlaceSchema = z.object({
  name: z.string().min(1),
  lat: z.number().finite().gte(-90).lte(90),
  lon: z.number().finite().gte(-180).lte(180),
});

export const PositionSchema = z.object({
  id: z.string().min(1),
  lat: z.number().finite().gte(-90).lte(90),
  lon: z.number().finite().gte(-180).lte(180),
  capturedAt: z.string().datetime({ offset: true }),
  receivedAt: z.string().datetime({ offset: true }),
  accuracyM: z.number().finite().nonnegative(),
  speedMps: z.number().finite().nonnegative().nullable(),
  speedAccuracyMps: z.number().finite().nonnegative().nullable(),
  source: z.string().nullable(),
  sequence: z.number().int().nonnegative(),
  isOutlier: z.boolean(),
  coordinateSystem: z.literal('WGS84'),
});

export const MapProviderSchema = z.enum(['OSM', 'AMAP']);

export const PublicTripSchema = z.object({
  title: z.string(),
  origin: PlaceSchema.nullable(),
  destination: PlaceSchema.nullable(),
  status: z.enum(['active', 'ended']),
  startedAt: z.string().datetime({ offset: true }),
  endedAt: z.string().datetime({ offset: true }).nullable(),
  sampleIntervalSec: z.number().int().positive(),
  uploadIntervalSec: z.number().int().positive(),
  mode: z.enum(['standard', 'detailed']),
  mapProvider: MapProviderSchema.optional(),
  latestPositionAt: z.string().datetime({ offset: true }).nullable(),
  latestPositionLabel: z.string().trim().min(1).nullable().optional(),
  pointCount: z.number().int().nonnegative(),
  latestPosition: PositionSchema.nullable(),
});

export const PublicTripResponseSchema = z.object({ trip: PublicTripSchema });
export const PositionsResponseSchema = z.object({
  points: z.array(PositionSchema),
  nextCursor: z.number().int().nonnegative().nullable(),
  hasMore: z.boolean(),
});

export type Place = z.infer<typeof PlaceSchema>;
export type Position = z.infer<typeof PositionSchema>;
export type PublicTrip = z.infer<typeof PublicTripSchema>;
export type PositionsResponse = z.infer<typeof PositionsResponseSchema>;
export type MapProviderValue = z.infer<typeof MapProviderSchema>;
export type TripUiState = 'ACTIVE' | 'STALE' | 'ENDED';
