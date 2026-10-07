// Shared traffic model for both load tests, so that the centralized and the geo-distributed runs
// differ only in where requests go and whether X-User-Region is sent.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const REGIONS = ['AMER', 'EURO', 'ASIA'];
const GENRES = ['rock', 'jazz', 'electronic', 'hip-hop', 'classical', 'pop'];

export const readLatency = new Trend('geo_read_latency', true);
export const writeLatency = new Trend('geo_write_latency', true);
export const readsServedByMaster = new Counter('geo_reads_served_by_master');
export const failedOperations = new Rate('geo_failed_operations');

const READ_RATIO = Number(__ENV.READ_RATIO || 0.8);

/**
 * Builds scenario options: one constant-arrival-rate scenario per region, all calling `execName`.
 */
export function buildOptions(execName) {
  const rate = Number(__ENV.RATE_PER_REGION || 50);
  const duration = __ENV.DURATION || '2m';
  const scenarios = {};
  for (const region of REGIONS) {
    scenarios[`region_${region.toLowerCase()}`] = {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 20),
      maxVUs: Number(__ENV.MAX_VUS || 200),
      exec: execName,
      env: { REGION: region },
      tags: { region },
    };
  }
  return {
    scenarios,
    setupTimeout: '5m',
    thresholds: {
      http_req_failed: ['rate<0.02'],
      geo_failed_operations: ['rate<0.02'],
      geo_read_latency: ['p(95)<300'],
      geo_write_latency: ['p(95)<500'],
    },
  };
}

function params(target, region, name, op) {
  const headers = { 'Content-Type': 'application/json' };
  if (target.sendRegionHeader) {
    headers['X-User-Region'] = region;
  }
  return { headers, tags: { name, op, region } };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

// __VU/__ITER are not available in setup(), so uniqueness comes from a per-VU counter plus randomness.
let sequence = 0;

function unique() {
  sequence += 1;
  return `${Date.now()}-${sequence}-${Math.floor(Math.random() * 1e9)}`;
}

function post(target, region, path, body, name) {
  return http.post(`${target.baseUrl(region)}${path}`, JSON.stringify(body), params(target, region, name, 'write'));
}

function idOf(res) {
  return res.status === 200 || res.status === 201 ? res.json('id') : null;
}

export function createArtist(target, region, index) {
  return idOf(post(target, region, '/api/v1/artists',
    { name: `Artist ${region} ${index}`, originRegion: region, genre: GENRES[index % GENRES.length] },
    'POST /api/v1/artists'));
}

export function createAlbum(target, region, artistId) {
  return post(target, region, '/api/v1/albums', {
    artistId,
    title: `Album ${unique()}`,
    releaseYear: 1960 + Math.floor(Math.random() * 65),
    regionCode: region,
  }, 'POST /api/v1/albums');
}

export function createUser(target, region) {
  const suffix = unique();
  return post(target, region, '/api/v1/users', {
    username: `user-${region.toLowerCase()}-${suffix}`,
    email: `user-${suffix}@${region.toLowerCase()}.example.com`,
    homeRegion: region,
  }, 'POST /api/v1/users');
}

export function addFavorite(target, region, userId, albumId) {
  return http.post(`${target.baseUrl(region)}/api/v1/users/${userId}/favorites/${albumId}`, null,
    params(target, region, 'POST /api/v1/users/{id}/favorites/{albumId}', 'write'));
}

/**
 * Creates artists, albums and users in every region (each on its own regional master) and waits for
 * cross-region replication so that the read phase can find every album from every region.
 */
export function seed(target) {
  const artistsPerRegion = Number(__ENV.SEED_ARTISTS || 6);
  const albumsPerRegion = Number(__ENV.SEED_ALBUMS || 60);
  const usersPerRegion = Number(__ENV.SEED_USERS || 60);
  const data = { artists: {}, albums: [], users: {} };

  for (const region of REGIONS) {
    data.artists[region] = [];
    for (let i = 0; i < artistsPerRegion; i++) {
      const id = createArtist(target, region, i);
      if (id) data.artists[region].push(id);
    }
    if (data.artists[region].length === 0) {
      throw new Error(`Seeding failed: no artists created in ${region} (${target.baseUrl(region)})`);
    }
    for (let i = 0; i < albumsPerRegion; i++) {
      const id = idOf(createAlbum(target, region, pick(data.artists[region])));
      if (id) data.albums.push(id);
    }
    data.users[region] = [];
    for (let i = 0; i < usersPerRegion; i++) {
      const id = idOf(createUser(target, region));
      if (id) data.users[region].push(id);
    }
  }

  sleep(Number(__ENV.REPLICATION_WAIT_SECONDS || 3));

  for (const region of REGIONS) {
    for (const userId of data.users[region].slice(0, 20)) {
      addFavorite(target, region, userId, pick(data.albums));
    }
  }
  return data;
}

function recordRead(res, ok) {
  readLatency.add(res.timings.duration);
  failedOperations.add(!ok);
  const node = res.headers['X-Db-Node'] || '';
  if (ok && node.endsWith('-master')) {
    readsServedByMaster.add(1);
  }
}

function recordWrite(res, ok) {
  writeLatency.add(res.timings.duration);
  failedOperations.add(!ok);
}

function read(target, region, data) {
  if (Math.random() < 0.5) {
    const res = http.get(`${target.baseUrl(region)}/api/v1/albums/${pick(data.albums)}`,
      params(target, region, 'GET /api/v1/albums/{id}', 'read'));
    recordRead(res, check(res, { 'album read 200': (r) => r.status === 200 }));
  } else {
    const res = http.get(`${target.baseUrl(region)}/api/v1/users/${pick(data.users[region])}/feed?limit=20`,
      params(target, region, 'GET /api/v1/users/{id}/feed', 'read'));
    recordRead(res, check(res, { 'feed read 200': (r) => r.status === 200 }));
  }
}

function write(target, region, data) {
  const roll = Math.random();
  let res;
  if (roll < 0.4) {
    res = createUser(target, region);
  } else if (roll < 0.7) {
    res = createAlbum(target, region, pick(data.artists[region]));
  } else {
    res = addFavorite(target, region, pick(data.users[region]), pick(data.albums));
  }
  recordWrite(res, check(res, { 'write 2xx': (r) => r.status === 200 || r.status === 201 }));
}

/**
 * One iteration of a user from `region`: READ_RATIO (80%) reads, the rest writes into the user's own region.
 */
export function regionIteration(target, data) {
  const region = __ENV.REGION;
  if (Math.random() < READ_RATIO) {
    read(target, region, data);
  } else {
    write(target, region, data);
  }
}
