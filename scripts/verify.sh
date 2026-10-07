#!/usr/bin/env bash
# End-to-end check of the whole stand: clean start with `docker compose up --build`, API smoke test,
# fail-over, observability (Prometheus, Grafana, Tempo) and a short k6 run.
# Usage: bash scripts/verify.sh 2>&1 | tee verify.log
set -uo pipefail

cd "$(dirname "$0")/.."

GEO=http://localhost:8080
CENTRAL=http://localhost:8081
JSON='Content-Type: application/json'
PASSED=0
FAILED=0
RESULTS=()

check() {
  local name=$1 ok=$2 detail=${3:-}
  if [ "$ok" = "1" ]; then
    PASSED=$((PASSED + 1)); RESULTS+=("PASS  $name  $detail")
  else
    FAILED=$((FAILED + 1)); RESULTS+=("FAIL  $name  $detail")
  fi
  echo "[$([ "$ok" = "1" ] && echo PASS || echo FAIL)] $name $detail"
}

json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" 2>/dev/null; }
db_node() { curl -s -o /dev/null -D - "$@" | grep -i '^x-db-node' | awk '{print $2}' | tr -d '\r'; }
wait_for() {
  local name=$1 cmd=$2 timeout=$3
  for _ in $(seq 1 "$timeout"); do
    if eval "$cmd" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Timeout waiting for $name"
  return 1
}

echo "=== 0. Environment"
docker --version; docker compose version
pkill -f geo-music-api.jar 2>/dev/null && echo "stopped locally running jar"

echo "=== 1. Clean start (docker compose down -v; up -d --build)"
docker compose down -v --remove-orphans >/dev/null 2>&1
start=$(date +%s)
docker compose up -d --build 2>&1 | tail -n 25
rc=${PIPESTATUS[0]}
check "compose up --build" "$([ "$rc" = "0" ] && echo 1 || echo 0)" "($(( $(date +%s) - start ))s)"

echo "=== 2. Waiting for applications and replication"
wait_for app-geo "curl -sf $GEO/actuator/health" 300; check "app-geo healthy" "$([ $? = 0 ] && echo 1 || echo 0)"
wait_for app-central "curl -sf $CENTRAL/actuator/health" 300; check "app-central healthy" "$([ $? = 0 ] && echo 1 || echo 0)"
wait_for replication "docker compose logs replication-setup | grep -q 'mesh is ready'" 180
check "logical replication mesh" "$([ $? = 0 ] && echo 1 || echo 0)"
# pg_stat_replication lists both physical standbys and logical subscribers; logical ones hold a logical slot.
streaming=0
logical=0
for r in amer euro asia; do
  counts=$(docker compose exec -T $r-master psql -U geo -d geomusic -tAc "
    select count(*) filter (where s.slot_type is distinct from 'logical') || ' ' ||
           count(*) filter (where s.slot_type = 'logical')
    from pg_stat_replication r left join pg_replication_slots s on s.active_pid = r.pid
    where r.state = 'streaming'" 2>/dev/null)
  read -r physical_n logical_n <<< "$counts"
  streaming=$((streaming + ${physical_n:-0}))
  logical=$((logical + ${logical_n:-0}))
done
check "6 streaming replicas (physical)" "$([ "$streaming" = "6" ] && echo 1 || echo 0)" "(found $streaming)"
check "6 logical subscriptions (cross-region)" "$([ "$logical" = "6" ] && echo 1 || echo 0)" "(found $logical)"
sleep 6

echo "=== 3. Sharded writes and cross-region reads"
ARTIST=$(curl -s -X POST $GEO/api/v1/artists -H "$JSON" -d '{"name":"Yoko Kanno","originRegion":"ASIA","genre":"jazz"}' | json "d['id']")
node=$(db_node -X POST $GEO/api/v1/albums -H "$JSON" -H 'X-User-Region: EURO' \
  -d "{\"artistId\":\"$ARTIST\",\"title\":\"Cowboy Bebop OST\",\"releaseYear\":1998,\"regionCode\":\"ASIA\"}")
ALBUM=$(curl -s -X POST $GEO/api/v1/albums -H "$JSON" \
  -d "{\"artistId\":\"$ARTIST\",\"title\":\"Tank!\",\"releaseYear\":1998,\"regionCode\":\"ASIA\"}" | json "d['id']")
check "album write -> asia-master" "$([ "$node" = "asia-master" ] && echo 1 || echo 0)" "($node)"
USER_ID=$(curl -s -X POST $GEO/api/v1/users -H "$JSON" -H 'X-User-Region: AMER' \
  -d '{"username":"anna","email":"anna@example.com","homeRegion":"EURO"}' | json "d['id']")
check "user created" "$([ -n "$USER_ID" ] && echo 1 || echo 0)" "($USER_ID)"
sleep 2
n1=$(db_node $GEO/api/v1/albums/$ALBUM -H 'X-User-Region: EURO')
n2=$(db_node $GEO/api/v1/albums/$ALBUM -H 'X-User-Region: EURO')
check "ASIA album read from EURO replicas (round-robin)" \
  "$([[ "$n1" == euro-replica* && "$n2" == euro-replica* && "$n1" != "$n2" ]] && echo 1 || echo 0)" "($n1, $n2)"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST $GEO/api/v1/users/$USER_ID/favorites/$ALBUM -H 'X-User-Region: ASIA')
check "favorite added" "$([ "$code" = "201" ] && echo 1 || echo 0)" "(HTTP $code)"
sleep 2
fav=$(curl -s $GEO/api/v1/users/$USER_ID/feed -H 'X-User-Region: ASIA' | json "len(d['favorites'])")
check "feed from ASIA replicas contains favorite" "$([ "$fav" = "1" ] && echo 1 || echo 0)" "(favorites=$fav)"

echo "=== 4. Error handling (ProblemDetail)"
code=$(curl -s -o /dev/null -w '%{http_code}' $GEO/api/v1/albums/$ALBUM -H 'X-User-Region: MARS')
check "invalid region -> 400" "$([ "$code" = "400" ] && echo 1 || echo 0)"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST $GEO/api/v1/users -H "$JSON" -d '{"username":"","email":"x","homeRegion":"ASIA"}')
check "invalid body -> 400" "$([ "$code" = "400" ] && echo 1 || echo 0)"

echo "=== 5. Fail-over"
docker compose stop euro-replica1 >/dev/null 2>&1
n1=$(db_node $GEO/api/v1/albums/$ALBUM -H 'X-User-Region: EURO')
n2=$(db_node $GEO/api/v1/albums/$ALBUM -H 'X-User-Region: EURO')
check "replica1 down -> reads from euro-replica2" "$([ "$n1" = "euro-replica2" ] && [ "$n2" = "euro-replica2" ] && echo 1 || echo 0)" "($n1, $n2)"
docker compose stop euro-replica2 >/dev/null 2>&1
n1=$(db_node $GEO/api/v1/albums/$ALBUM -H 'X-User-Region: EURO')
check "all replicas down -> reads from euro-master" "$([ "$n1" = "euro-master" ] && echo 1 || echo 0)" "($n1)"
docker compose stop asia-master >/dev/null 2>&1
body=$(curl -s -D /tmp/verify-503.txt -X POST $GEO/api/v1/users -H "$JSON" -d '{"username":"kenji","email":"k@example.com","homeRegion":"ASIA"}')
status=$(echo "$body" | json "d['status']")
ctype=$(grep -i '^content-type' /tmp/verify-503.txt | tr -d '\r' | awk '{print $2}')
check "asia-master down -> 503 problem+json" "$([ "$status" = "503" ] && [ "$ctype" = "application/problem+json" ] && echo 1 || echo 0)" "($status, $ctype)"
docker compose start euro-replica1 euro-replica2 asia-master >/dev/null 2>&1
sleep 15
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST $GEO/api/v1/users -H "$JSON" -d '{"username":"kenji","email":"k@example.com","homeRegion":"ASIA"}')
check "recovery: ASIA write works again" "$([ "$code" = "201" ] && echo 1 || echo 0)" "(HTTP $code)"

echo "=== 6. Observability"
up=$(curl -s 'http://localhost:9090/api/v1/query?query=up' | json "sum(int(r['value'][1]) for r in d['data']['result'])")
check "Prometheus scrapes both apps" "$([ "$up" = "2" ] && echo 1 || echo 0)" "(up=$up)"
tags=$(curl -s "$GEO/actuator/prometheus" | grep -c 'http_server_requests_seconds_count{.*db_node_type=.*user_region=')
check "HTTP metrics have region/node labels" "$([ "$tags" -gt 0 ] && echo 1 || echo 0)" "($tags series)"
grafana=$(curl -s http://localhost:3000/api/health | json "d['database']")
check "Grafana healthy" "$([ "$grafana" = "ok" ] && echo 1 || echo 0)"
dash=$(curl -s 'http://localhost:3000/api/search?query=Geo%20Music' | json "len(d)")
check "Grafana dashboard provisioned" "$([ "${dash:-0}" -ge 1 ] && echo 1 || echo 0)"
sleep 10
traces=$(curl -s 'http://localhost:3200/api/search?tags=service.name%3Dgeo-music-api&limit=20' | json "len(d.get('traces', []))")
check "Traces in Tempo" "$([ "${traces:-0}" -ge 1 ] && echo 1 || echo 0)" "(${traces:-0} found)"

echo "=== 7. k6 (20s each)"
K6_OPTS="-e RATE_PER_REGION=20 -e DURATION=20s -e SEED_ALBUMS=20 -e SEED_USERS=20 --quiet"
for test in geo_distributed_test centralized_test; do
  if command -v k6 >/dev/null 2>&1 || [ -x /snap/bin/k6 ]; then
    K6=$(command -v k6 || echo /snap/bin/k6)
    $K6 run $K6_OPTS -e BASE_URL=$GEO -e CENTRAL_BASE_URL=$CENTRAL k6/$test.js > /tmp/verify-$test.txt 2>&1
  else
    docker compose run --rm k6 run $K6_OPTS /scripts/$test.js > /tmp/verify-$test.txt 2>&1
  fi
  rc=$?
  grep -E 'checks_succeeded|geo_read_latency\.|geo_write_latency\.' /tmp/verify-$test.txt
  check "k6 $test" "$([ $rc = 0 ] && echo 1 || echo 0)" "(exit $rc)"
done

echo
echo "================ SUMMARY"
printf '%s\n' "${RESULTS[@]}"
echo "PASSED: $PASSED  FAILED: $FAILED"
[ "$FAILED" = "0" ] && echo "VERDICT: OK" || echo "VERDICT: PROBLEMS FOUND"
