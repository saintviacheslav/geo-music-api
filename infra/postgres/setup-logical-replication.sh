#!/bin/bash
# Builds a full mesh of logical replication between the regional masters:
#   every master publishes its tables and subscribes to the other two masters.
# As a result every region (and its physical replicas) serves the data of all shards for reads,
# while each row is still written only on the master of its own region.
#
# origin = none (PostgreSQL 16+) forwards only locally originated changes, which prevents replication loops.
# The script waits until the application (Flyway) has created the schema on every master and is idempotent.
set -euo pipefail

read -r -a REGIONS <<< "${REGIONS:-amer euro asia}"
PUBLICATION="geo_shard_pub"
TABLES="artists, albums, users, user_favorite_albums"

query() {
  local host=$1
  shift
  psql -h "$host" -v ON_ERROR_STOP=1 -tAq "$@"
}

for region in "${REGIONS[@]}"; do
  host="${region}-master"
  echo "Waiting for schema on ${host}..."
  until [ "$(query "$host" -c "select to_regclass('public.user_favorite_albums') is not null" 2>/dev/null)" = "t" ]; do
    sleep 2
  done
done

for region in "${REGIONS[@]}"; do
  host="${region}-master"
  if [ "$(query "$host" -c "select count(*) from pg_publication where pubname = '${PUBLICATION}'")" = "0" ]; then
    query "$host" -c "CREATE PUBLICATION ${PUBLICATION} FOR TABLE ${TABLES}"
    echo "Publication ${PUBLICATION} created on ${host}"
  fi
done

for subscriber in "${REGIONS[@]}"; do
  for publisher in "${REGIONS[@]}"; do
    [ "$subscriber" = "$publisher" ] && continue
    subscription="${subscriber}_from_${publisher}"
    if [ "$(query "${subscriber}-master" -c "select count(*) from pg_subscription where subname = '${subscription}'")" = "0" ]; then
      query "${subscriber}-master" -c "CREATE SUBSCRIPTION ${subscription}
          CONNECTION 'host=${publisher}-master port=5432 dbname=${PGDATABASE} user=${PGUSER} password=${PGPASSWORD}'
          PUBLICATION ${PUBLICATION}
          WITH (origin = none, copy_data = false)"
      echo "Subscription ${subscription} created"
    fi
  done
done

echo "Logical replication mesh is ready"
