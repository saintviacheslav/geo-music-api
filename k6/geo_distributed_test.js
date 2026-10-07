// Geo-distributed load: users of AMER, EURO and ASIA send X-User-Region, so
//   * 80% reads are served by the replicas of their own region (round-robin),
//   * 20% writes go to the master of their own region.
//
// Each region may have its own application endpoint (an app instance deployed next to the region):
//   AMER_BASE_URL, EURO_BASE_URL, ASIA_BASE_URL, falling back to BASE_URL.
//
// k6 run k6/geo_distributed_test.js
// k6 run -e BASE_URL=http://localhost:8080 -e RATE_PER_REGION=100 -e DURATION=5m k6/geo_distributed_test.js
import { buildOptions, regionIteration, seed } from './lib/common.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ENDPOINTS = {
  AMER: __ENV.AMER_BASE_URL || BASE_URL,
  EURO: __ENV.EURO_BASE_URL || BASE_URL,
  ASIA: __ENV.ASIA_BASE_URL || BASE_URL,
};

const target = {
  baseUrl: (region) => ENDPOINTS[region],
  sendRegionHeader: true,
};

export const options = buildOptions('regionTraffic');

export function setup() {
  return seed(target);
}

export function regionTraffic(data) {
  regionIteration(target, data);
}
