CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL UNIQUE,
  credential_hash TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trips (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  origin_name TEXT,
  origin_lat REAL,
  origin_lon REAL,
  destination_name TEXT,
  destination_lat REAL,
  destination_lon REAL,
  status TEXT NOT NULL CHECK (status IN ('active', 'ended')),
  started_at TEXT NOT NULL,
  ended_at TEXT,
  end_reason TEXT CHECK (end_reason IN ('manual', 'expired') OR end_reason IS NULL),
  sample_interval_sec INTEGER NOT NULL,
  upload_interval_sec INTEGER NOT NULL,
  mode TEXT NOT NULL CHECK (mode IN ('standard', 'detailed')),
  max_share_seconds INTEGER NOT NULL,
  share_token_hash TEXT NOT NULL UNIQUE,
  share_expires_at TEXT NOT NULL,
  share_revoked_at TEXT,
  CHECK ((origin_name IS NULL AND origin_lat IS NULL AND origin_lon IS NULL) OR
         (origin_name IS NOT NULL AND origin_lat IS NOT NULL AND origin_lon IS NOT NULL)),
  CHECK ((destination_name IS NULL AND destination_lat IS NULL AND destination_lon IS NULL) OR
         (destination_name IS NOT NULL AND destination_lat IS NOT NULL AND destination_lon IS NOT NULL)),
  CHECK ((status = 'active' AND ended_at IS NULL AND end_reason IS NULL) OR
         (status = 'ended' AND ended_at IS NOT NULL AND end_reason IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS one_active_trip_per_device
  ON trips(device_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS trips_device_started_idx ON trips(device_id, started_at DESC);
CREATE INDEX IF NOT EXISTS trips_retention_idx ON trips(status, ended_at, started_at);

CREATE TABLE IF NOT EXISTS positions (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  id TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  captured_at TEXT NOT NULL,
  received_at TEXT NOT NULL,
  accuracy_m REAL NOT NULL,
  speed_mps REAL,
  speed_accuracy_mps REAL,
  source TEXT,
  coordinate_system TEXT NOT NULL CHECK (coordinate_system = 'WGS84'),
  is_outlier INTEGER NOT NULL DEFAULT 0 CHECK (is_outlier IN (0, 1)),
  UNIQUE(trip_id, id)
);
CREATE INDEX IF NOT EXISTS positions_trip_captured_idx ON positions(trip_id, captured_at, id);
CREATE INDEX IF NOT EXISTS positions_trip_sequence_idx ON positions(trip_id, sequence);
