// Centralized baseline: users of all three regions hit one central application whose nine connection
// strings point to a single PostgreSQL node (app-central + central-db in docker-compose.yml).
// No X-User-Region header is sent — a central node knows nothing about regions. The traffic mix
// (80% reads / 20% writes, same endpoints, same data volume) is identical to geo_distributed_test.js,
// so the two runs can be compared directly.
//
// k6 run k6/centralized_test.js
// k6 run -e CENTRAL_BASE_URL=http://localhost:8081 -e RATE_PER_REGION=100 -e DURATION=5m k6/centralized_test.js
import { buildOptions, regionIteration, seed } from './lib/common.js';

const CENTRAL_BASE_URL = __ENV.CENTRAL_BASE_URL || 'http://localhost:8081';

const target = {
  baseUrl: () => CENTRAL_BASE_URL,
  sendRegionHeader: false,
};

export const options = buildOptions('regionTraffic');

export function setup() {
  return seed(target);
}

export function regionTraffic(data) {
  regionIteration(target, data);
}
