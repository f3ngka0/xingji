# v1 API contract

This file is the integration contract for `server/`, `android/`, and `web/`. All coordinates on the wire and in storage are WGS-84 decimal latitude/longitude. JSON timestamps are ISO-8601 UTC. API errors use `{ "error": { "code": "...", "message": "..." } }`.

## Device management

`POST /api/v1/devices` with `{ "installationId": "random UUID" }` returns `{ "deviceId": "UUID", "credential": "opaque random secret" }`. Only the first registration returns a credential. The client stores it in Android Keystore-backed encrypted storage. All management calls use `Authorization: Bearer <credential>`. Public calls never accept that credential.

## Trips

`POST /api/v1/trips` accepts `{ "origin": { "name": "...", "lat": 23.0, "lon": 111.0 } | null, "destination": { "name": "...", "lat": 23.0, "lon": 111.0 } | null, "sampleIntervalSec": 300, "uploadIntervalSec": 300, "mode": "standard" | "detailed", "maxShareSeconds": 86400, "mapProvider": "OSM" | "AMAP" }`. Destination and origin may be null. `mapProvider` records the map family the trip was created with (defaults to `OSM`); history always renders with its own provider. The origin, when present, is the device's first real fix of the trip (with a system-derived display name such as `当前位置` when no name can be resolved), never a user-picked search result. Returns `{ "trip": Trip, "shareUrl": "https://.../trip/<token>" }`.

`GET /api/v1/trips` returns `{ "trips": Trip[] }`; `GET /api/v1/trips/:id` returns `{ "trip": Trip }`; `PATCH /api/v1/trips/:id/settings` accepts `{ "sampleIntervalSec": number, "uploadIntervalSec": number, "mode": "standard" | "detailed", "maxShareSeconds": number }` and returns `{ "trip": Trip }`. `PATCH /api/v1/trips/:id/destination` accepts `{ "destination": Place | null }` on an active trip (add, change, or clear; clearing never affects position recording) and returns `{ "trip": Trip }`. `POST /api/v1/trips/:id/end` and `POST /api/v1/trips/:id/revoke` return `{ "trip": Trip }`. `DELETE /api/v1/trips/:id` returns HTTP 204.

`Trip` has `id`, `title`, `origin` and nullable `destination`, `status` (`active` or `ended`), `startedAt`, nullable `endedAt`, `endReason` (`manual` or `expired` or null), `sampleIntervalSec`, `uploadIntervalSec`, `mode`, `maxShareSeconds`, `mapProvider` (`OSM` or `AMAP`), nullable `shareExpiresAt`, `latestPositionAt`, `latestPositionLabel` (nullable server-resolved area name for the latest point), `pointCount`, and `shareRevokedAt`. Management responses may include `shareUrl`; public responses must not include the share token, credential, device ID, or private identifiers.

## Position upload

`POST /api/v1/trips/:id/positions` accepts `{ "points": PositionInput[] }` (1-100). `PositionInput` has `id` (client UUID), `lat`, `lon`, `capturedAt`, `accuracyM`, nullable `speedMps`, nullable `speedAccuracyMps`, nullable `source`, `coordinateSystem: "WGS84"`. Returns `{ "acceptedIds": string[], "duplicateIds": string[], "rejected": [{ "id": string, "code": string }] }`. Each ID is unique per trip and repeated uploads are idempotent. All successfully sampled points are saved locally before upload. The server adds `receivedAt` and a stable increasing `sequence`. Public `Position` includes these fields plus `isOutlier` and `coordinateSystem`.

## Public read-only API

`GET /api/v1/public/trips/:token` returns `{ "trip": PublicTrip }`, where `PublicTrip` contains `title`, `origin`, nullable `destination`, `status`, `startedAt`, nullable `endedAt`, `sampleIntervalSec`, `uploadIntervalSec`, `mode`, `mapProvider` (`OSM` or `AMAP`), `latestPositionAt`, nullable `latestPositionLabel`, `pointCount` and `latestPosition` (nullable `Position`). It contains no management fields or token. Renderers choose the map family from `mapProvider` so the viewer sees the same map semantics as the trip owner.

`GET /api/v1/public/trips/:token/positions?after=<sequence>&limit=500` returns `{ "points": Position[], "nextCursor": number | null, "hasMore": boolean }`. Omitted `after` starts at 0. Order is by `capturedAt`, then ID for full history; incrementally added points use stable `sequence`, so clients should merge by point ID and sort by `capturedAt`. To handle late offline uploads, the server may return already-seen points when the client requests an overlap window; clients deduplicate by ID.

The share URL serves the web app at `/trip/:token`. An invalid, revoked, expired or deleted token must not reveal trip data.

## Constraints and semantics

- Standard mode uses the requested sample interval. Detailed mode may use 60-second samples and batches uploads at the upload interval. Server validates supported intervals and max sharing time.
- Server expiry ends active trips after `startedAt + maxShareSeconds`, independent of client uptime. Share-link expiration is separate from trip end, so history remains readable until its own expiry or revocation.
- Outliers remain stored, but map renderers omit them from the normal polyline. A long gap may be shown as a dashed connector, never as a predicted route.
- Missing speed is `null`, never zero. For display only, multiply m/s by 3.6.
