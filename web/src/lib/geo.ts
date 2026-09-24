import type { Position } from '../types';

const EARTH_A = 6_378_245;
const ECCENTRICITY_SQUARED = 0.00669342162296594323;

function transformLatitude(x: number, y: number) {
  let value = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  value += ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3;
  value += ((20 * Math.sin(y * Math.PI) + 40 * Math.sin((y / 3) * Math.PI)) * 2) / 3;
  value += ((160 * Math.sin((y / 12) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30)) * 2) / 3;
  return value;
}

function transformLongitude(x: number, y: number) {
  let value = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  value += ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3;
  value += ((20 * Math.sin(x * Math.PI) + 40 * Math.sin((x / 3) * Math.PI)) * 2) / 3;
  value += ((150 * Math.sin((x / 12) * Math.PI) + 300 * Math.sin((x / 30) * Math.PI)) * 2) / 3;
  return value;
}

export function wgs84ToGcj02(lon: number, lat: number): [number, number] {
  if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return [lon, lat];

  let deltaLat = transformLatitude(lon - 105, lat - 35);
  let deltaLon = transformLongitude(lon - 105, lat - 35);
  const radians = (lat / 180) * Math.PI;
  let magic = Math.sin(radians);
  magic = 1 - ECCENTRICITY_SQUARED * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  deltaLat = (deltaLat * 180) / (((EARTH_A * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic)) * Math.PI);
  deltaLon = (deltaLon * 180) / ((EARTH_A / sqrtMagic) * Math.cos(radians) * Math.PI);
  return [lon + deltaLon, lat + deltaLat];
}

export function sortPositions(points: Position[]) {
  return [...points].sort((left, right) => {
    const timeOrder = Date.parse(left.capturedAt) - Date.parse(right.capturedAt);
    return timeOrder === 0 ? left.id.localeCompare(right.id) : timeOrder;
  });
}

export function getLongGapThresholdMs(sampleIntervalSec: number) {
  return Math.max(sampleIntervalSec * 2 + 120, 300) * 1000;
}
