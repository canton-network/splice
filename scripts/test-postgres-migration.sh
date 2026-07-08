#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# End-to-end test of the PostgreSQL major-version migration documented at
# https://docs.canton.network/global-synchronizer/production-operations/validator-postgres-migration, run against LocalNet.
#
# Flow: LocalNet on postgres:$SRC_PG -> seed wallet data (tap + cross-participant
# transfer) -> stop applications -> pg_dump every database with the target-version
# client -> fresh postgres:$TGT_PG -> pg_restore -> restart -> assert that balances
# are preserved and a new transfer succeeds.
#
# WARNING: tears down any running LocalNet compose project (down -v) at start.
#
# usage: [SRC_PG=14] [TGT_PG=17] [IMAGE_TAG=0.6.11] scripts/test-postgres-migration.sh

set -euo pipefail

SRC_PG=${SRC_PG:-14}
TGT_PG=${TGT_PG:-17}
export IMAGE_TAG=${IMAGE_TAG:-0.6.11}

REPO_ROOT=$(git rev-parse --show-toplevel)
export LOCALNET_DIR=${LOCALNET_DIR:-$REPO_ROOT/cluster/compose/localnet}
DUMPS=$(mktemp -d -t pgmig-dumps-XXXXXX)
DB_PASSWORD=supersafe

echo "migrating postgres:${SRC_PG} -> postgres:${TGT_PG} (splice ${IMAGE_TAG}); dumps in ${DUMPS}"

compose() {
  docker compose --env-file "$LOCALNET_DIR/compose.env" \
                 --env-file "$LOCALNET_DIR/env/common.env" \
                 -f "$LOCALNET_DIR/compose.yaml" \
                 -f "$LOCALNET_DIR/resource-constraints.yaml" \
                 --profile sv --profile app-provider --profile app-user "$@"
}

# Mint an unsafe-auth HS256 JWT and call a wallet API through nginx.
# usage: wallet <port> <user> <method> <path> [json-body]
wallet() {
  local port=$1 user=$2 method=$3 path=$4 body=${5:-}
  local header payload sig token
  b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
  header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
  payload=$(printf '{"sub":"%s","aud":"https://canton.network.global","exp":4102444800}' "$user" | b64url)
  sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "unsafe" -binary | b64url)
  token="$header.$payload.$sig"
  local args=(-s --fail-with-body -X "$method" "http://127.0.0.1:${port}${path}"
    -H "Host: wallet.localhost" -H "Authorization: Bearer $token")
  if [ -n "$body" ]; then args+=(-H "Content-Type: application/json" -d "$body"); fi
  curl "${args[@]}"
}

balance() { # port user -> effective_unlocked_qty
  wallet "$1" "$2" GET /api/validator/v0/wallet/balance \
    | grep -o '"effective_unlocked_qty":"[^"]*"' | cut -d'"' -f4
}

wait_healthy() {
  local unhealthy
  for _ in $(seq 60); do
    unhealthy=$(docker ps --format '{{.Names}} {{.Status}}' | grep -cv '(healthy)' || true)
    [ "$unhealthy" -le 1 ] && return 0   # nginx has no healthcheck
    sleep 5
  done
  echo "FAIL: containers did not become healthy"
  docker ps
  exit 1
}

# Healthy containers do not imply onboarded wallets; wait for the wallet API too.
wait_wallet() { # port user
  local status
  for _ in $(seq 60); do
    status=$(wallet "$1" "$2" GET /api/validator/v0/wallet/user-status 2>/dev/null || true)
    grep -q '"user_wallet_installed":true' <<< "$status" && return 0
    sleep 5
  done
  echo "FAIL: wallet for $2 not ready, last status: $status"
  exit 1
}

tap() { # right after bootstrap taps fail until the first open mining round exists; retry
  for _ in $(seq 30); do
    wallet 2000 app-user POST /api/validator/v0/wallet/tap '{"amount":"1000.0"}' \
      | grep -q contract_id && return 0
    sleep 10
  done
  return 1
}

transfer() { # amount tracking_id: app-user -> app-provider, accepted by receiver
  local amount=$1 tracking_id=$2 provider cid=""
  provider=$(wallet 3000 app-provider GET /api/validator/v0/wallet/user-status \
    | grep -o '"party_id":"[^"]*"' | cut -d'"' -f4)
  wallet 2000 app-user POST /api/validator/v0/wallet/transfer-offers \
    "{\"receiver_party_id\":\"$provider\",\"amount\":\"$amount\",\"description\":\"$tracking_id\",\"expires_at\":4102444800000000,\"tracking_id\":\"$tracking_id\"}" \
    | grep -q offer_contract_id || return 1
  for _ in $(seq 30); do # receiver sees the offer only after ingestion
    cid=$(wallet 3000 app-provider GET /api/validator/v0/wallet/transfer-offers \
      | grep -o '"contract_id":"[^"]*"' | head -1 | cut -d'"' -f4)
    [ -n "$cid" ] && break
    sleep 2
  done
  [ -n "$cid" ] || return 1
  wallet 3000 app-provider POST "/api/validator/v0/wallet/transfer-offers/$cid/accept" '{}' \
    | grep -q accepted_offer_contract_id
}

echo "### 0. Clean slate, start LocalNet on postgres:${SRC_PG}"
POSTGRES_VERSION=$SRC_PG compose down -v --remove-orphans || true
docker volume rm -f "localnet_postgres_pg${SRC_PG}_backup" 2>/dev/null || true
POSTGRES_VERSION=$SRC_PG compose up -d
wait_healthy
docker exec postgres postgres --version

echo "### 1. Seed data: tap 1000 USD on app-user, transfer 12345 CC to app-provider"
wait_wallet 2000 app-user
wait_wallet 3000 app-provider
tap || { echo "FAIL: tap"; exit 1; }
sleep 3
transfer 12345.0 pgmig-pre || { echo "FAIL: pre-migration transfer"; exit 1; }
sleep 8
user_bal_before=$(balance 2000 app-user)
prov_bal_before=$(balance 3000 app-provider)
echo "pre-migration balances: app-user=$user_bal_before app-provider=$prov_bal_before"

wait_pg_healthy() {
  for _ in $(seq 30); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' postgres 2>/dev/null)" = "healthy" ] && return 0
    sleep 2
  done
  echo "FAIL: postgres did not become healthy"; exit 1
}

echo "### 2. Quiesce: stop everything except postgres"
compose stop
POSTGRES_VERSION=$SRC_PG compose start postgres
wait_pg_healthy
running=$(docker ps --format '{{.Names}}')
[ "$running" = "postgres" ] || { echo "FAIL: quiesce incomplete, still running: $running"; exit 1; }

echo "### 3. Dump all databases with the postgres:${TGT_PG} client"
docker exec postgres psql -U cnadmin -d postgres -tA \
  -c "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres'" > "$DUMPS/dblist.txt"
while read -r db; do
  docker run --rm --network localnet -e PGPASSWORD=$DB_PASSWORD -v "$DUMPS":/dumps \
    "postgres:$TGT_PG" pg_dump -h postgres -U cnadmin -Fc -f "/dumps/${db}.dump" "$db"
done < "$DUMPS/dblist.txt"
ls -lh "$DUMPS"

echo "### 4. Keep the pg${SRC_PG} volume as rollback point, remove original"
POSTGRES_VERSION=$SRC_PG compose stop postgres
docker rm -f postgres
docker volume create "localnet_postgres_pg${SRC_PG}_backup" > /dev/null
docker run --rm -v localnet_postgres:/from:ro -v "localnet_postgres_pg${SRC_PG}_backup":/to \
  alpine sh -c 'cp -a /from/. /to/'
docker volume rm localnet_postgres

echo "### 5. Fresh postgres:${TGT_PG} (entrypoint pre-creates empty databases)"
POSTGRES_VERSION=$TGT_PG compose up -d postgres
wait_pg_healthy
docker exec postgres postgres --version
# create databases the entrypoint did not pre-create (all exist on LocalNet;
# mirrors the migration guide, where start.sh-injected names are missing)
while read -r db; do
  docker exec postgres psql -U cnadmin -d postgres \
    -c "CREATE DATABASE \"${db}\"" 2>/dev/null || true
done < "$DUMPS/dblist.txt"

echo "### 6. Restore every dump"
while read -r db; do
  docker run --rm --network localnet -e PGPASSWORD=$DB_PASSWORD -v "$DUMPS":/dumps:ro \
    "postgres:$TGT_PG" pg_restore -h postgres -U cnadmin \
      --no-owner --no-privileges --exit-on-error -d "$db" "/dumps/${db}.dump"
  echo "restored: $db"
done < "$DUMPS/dblist.txt"

echo "### 7. Restart the full stack on postgres:${TGT_PG}"
POSTGRES_VERSION=$TGT_PG compose up -d
wait_healthy
wait_wallet 2000 app-user
wait_wallet 3000 app-provider

echo "### 8. Assertions"
user_bal_after=$(balance 2000 app-user)
prov_bal_after=$(balance 3000 app-provider)
echo "post-migration balances: app-user=$user_bal_after app-provider=$prov_bal_after"
# Balances only grow between the two checks (devnet reward issuance), so >= is the invariant.
awk -v a="$user_bal_after" -v b="$user_bal_before" 'BEGIN{exit !(a>=b)}' \
  || { echo "FAIL: app-user balance shrank across migration"; exit 1; }
awk -v a="$prov_bal_after" -v b="$prov_bal_before" 'BEGIN{exit !(a>=b)}' \
  || { echo "FAIL: app-provider balance shrank across migration"; exit 1; }

transfer 55.0 pgmig-post || { echo "FAIL: post-migration transfer"; exit 1; }

errors=$(docker logs splice --since 10m 2>&1 | grep -c '"level":"ERROR"' || true)
[ "$errors" -eq 0 ] || { echo "FAIL: $errors ERROR lines in splice logs"; exit 1; }

echo "PASS: migration postgres:${SRC_PG} -> postgres:${TGT_PG} verified"
