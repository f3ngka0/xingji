ALTER TABLE trips ADD COLUMN map_provider TEXT NOT NULL DEFAULT 'OSM' CHECK (map_provider IN ('OSM', 'AMAP'));
