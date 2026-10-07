#!/bin/bash
# Entrypoint of a hot-standby replica: clones the regional master with pg_basebackup on first start
# (-R writes primary_conninfo and standby.signal), then runs postgres as a streaming replica.
# PGPASSWORD (replication password) stays in the environment and is also used by the WAL receiver.
set -euo pipefail

PGDATA="${PGDATA:-/var/lib/postgresql/data}"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "Waiting for primary ${PRIMARY_HOST}..."
  until pg_isready -h "$PRIMARY_HOST" -p 5432 -U "$REPLICATION_USER" -q; do
    sleep 1
  done

  until pg_basebackup -h "$PRIMARY_HOST" -p 5432 -U "$REPLICATION_USER" -D "$PGDATA" -Fp -Xs -R; do
    echo "pg_basebackup from ${PRIMARY_HOST} failed, retrying..."
    rm -rf "${PGDATA:?}"/*
    sleep 2
  done
  chmod 0700 "$PGDATA"
  echo "Replica cloned from ${PRIMARY_HOST}"
fi

# A hot standby refuses to start if these are lower than on the primary (see master command in docker-compose.yml).
exec postgres \
  -c hot_standby=on \
  -c hot_standby_feedback=on \
  -c max_worker_processes=16 \
  -c max_wal_senders=16 \
  -c max_replication_slots=16
