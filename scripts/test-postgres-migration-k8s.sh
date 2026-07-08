#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Replays the Kubernetes section of the PostgreSQL 14 migration guide
# (https://docs.canton.network/global-synchronizer/production-operations/validator-postgres-migration)
# against a scratch cluster, verifying that the documented procedure works end-to-end.
# Adds the augmentations a scratch cluster needs on top of the documented commands:
# tolerations copied from a running pod, service-mesh sidecar injection disabled on
# client pods, the source password read from the secret, and values files recovered
# from the live releases.
#
# Prerequisites: kubectl context on the cluster, helm, jq, yq; a provisioned target
# PostgreSQL (cnadmin user with CREATEDB, cantonnet database, reachable from pods).
# The source instance is left untouched (decommissioning stays a manual step), and
# wallet-level verification (balances, a fresh transfer) stays manual.
#
# usage: NAMESPACE=validator1 TARGET_HOST=10.0.0.5 TARGET_PASSWORD=... \
#          scripts/test-postgres-migration-k8s.sh

set -euo pipefail

NAMESPACE=${NAMESPACE:?set NAMESPACE to the validator namespace}
TARGET_HOST=${TARGET_HOST:?set TARGET_HOST to the target PostgreSQL host or IP}
TARGET_PASSWORD=${TARGET_PASSWORD:?set TARGET_PASSWORD to the cnadmin password on the target}
SOURCE_HOST=${SOURCE_HOST:-postgres}                  # splice-postgres release/service name
SECRET_NAME=${SECRET_NAME:-postgres-secrets}
HELM_REPO=${HELM_REPO:-oci://ghcr.io/digital-asset/decentralized-canton-sync/helm}
PG_CLIENT_IMAGE=${PG_CLIENT_IMAGE:-postgres:17}

for tool in kubectl helm jq yq; do
  command -v "$tool" > /dev/null || { echo "FAIL: $tool not found"; exit 1; }
done

WORK=$(mktemp -d -t pgmig-k8s-XXXXXX)
echo "work dir: $WORK"

POSTGRES_PASSWORD=$(kubectl get secret "$SECRET_NAME" -n "$NAMESPACE" \
  -o jsonpath='{.data.postgresPassword}' | base64 -d)

# Tolerations from the running postgres pod; sidecar injection off for client pods.
TOLS=$(kubectl get pod "${SOURCE_HOST}-0" -n "$NAMESPACE" -o jsonpath='{.spec.tolerations}')
OVERRIDES="{\"metadata\":{\"annotations\":{\"sidecar.istio.io/inject\":\"false\"}},\"spec\":{\"tolerations\":${TOLS:-[]}}}"

pgpod() { # pgpod <name> <password> <args...>: run a one-off psql/bash pod
  local name=$1 password=$2
  shift 2
  kubectl run "$name" --rm -i --restart=Never -n "$NAMESPACE" \
    --image="$PG_CLIENT_IMAGE" --env=PGPASSWORD="$password" \
    --overrides="$OVERRIDES" -- "$@"
}

echo "### 0. Probe the target (reachability, CREATEDB, connection limit)"
pgpod pg-client "$TARGET_PASSWORD" psql -h "$TARGET_HOST" -U cnadmin -d cantonnet \
  -c 'CREATE DATABASE probe' -c 'DROP DATABASE probe' -c 'SHOW max_connections'

echo "### 1. Enumerate the databases"
pgpod pg-client "$POSTGRES_PASSWORD" psql -h "$SOURCE_HOST" -U cnadmin -d cantonnet -tA \
  -c "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres'" \
  | grep -vE '^$|^pod ' > "$WORK/dbs.txt"
cat "$WORK/dbs.txt"

echo "### 2. Stop the applications"
downtime_start=$(date +%s)
kubectl scale deployment --all --replicas=0 -n "$NAMESPACE"
for _ in $(seq 60); do
  running=$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null \
    | awk '$3 == "Running"' | grep -cv "^${SOURCE_HOST}-" || true)
  [ "$running" -eq 0 ] && break
  sleep 5
done
[ "$running" -eq 0 ] || { echo "FAIL: pods still running after quiesce"; kubectl get pods -n "$NAMESPACE"; exit 1; }

echo "### 3. Create the databases on the target"
while read -r db; do
  [ "$db" = "cantonnet" ] && continue
  pgpod pg-client "$TARGET_PASSWORD" psql -h "$TARGET_HOST" -U cnadmin -d cantonnet \
    -c "CREATE DATABASE \"${db}\""
done < "$WORK/dbs.txt"

echo "### 4. Copy each database"
while read -r db; do
  kubectl run pg-migrate --rm -i --restart=Never -n "$NAMESPACE" \
    --image="$PG_CLIENT_IMAGE" \
    --env=SOURCE_PGPASSWORD="$POSTGRES_PASSWORD" \
    --env=TARGET_PGPASSWORD="$TARGET_PASSWORD" \
    --overrides="$OVERRIDES" -- \
    bash -c "PGPASSWORD=\$SOURCE_PGPASSWORD pg_dump -h $SOURCE_HOST -U cnadmin -Fc '${db}' \
      | PGPASSWORD=\$TARGET_PGPASSWORD pg_restore -h $TARGET_HOST -U cnadmin \
          --no-owner --no-privileges --exit-on-error -d '${db}'" \
    || { echo "FAIL: copy of ${db}"; exit 1; }
  echo "copied: $db"
done < "$WORK/dbs.txt"

echo "### 5. Point the applications at the target"
kubectl create secret generic "$SECRET_NAME" \
  --from-literal=postgresPassword="$TARGET_PASSWORD" \
  -n "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

# Recover values from live releases; repoint every release whose persistence.host
# is the source instance. Participants upgrade first, mirroring install order.
helm list -n "$NAMESPACE" -o json \
  | jq -r '.[] | .name + " " + .chart' > "$WORK/releases.txt"
repoint() { # repoint <release> <chart-with-version>
  local release=$1 chart=$2 name version
  name=$(sed -E 's/-([0-9]+\.[0-9]+\..+)$//' <<< "$chart")
  version=$(sed -E 's/^.+-([0-9]+\.[0-9]+\..+)$/\1/' <<< "$chart")
  helm get values "$release" -n "$NAMESPACE" > "$WORK/${release}-values.yaml"
  [ "$(yq '.persistence.host // ""' "$WORK/${release}-values.yaml")" = "$SOURCE_HOST" ] || return 0
  yq -i ".persistence.host = \"$TARGET_HOST\" | .persistence.port = 5432" "$WORK/${release}-values.yaml"
  echo "upgrading $release ($name $version)"
  helm upgrade "$release" "$HELM_REPO/$name" --version "$version" \
    -f "$WORK/${release}-values.yaml" -n "$NAMESPACE" --wait --timeout 10m
}
while read -r release chart; do
  case "$chart" in splice-participant-*) repoint "$release" "$chart";; esac
done < "$WORK/releases.txt"
while read -r release chart; do
  case "$chart" in
    splice-participant-* | splice-postgres-*) ;;
    *) repoint "$release" "$chart";;
  esac
done < "$WORK/releases.txt"

echo "### 6. Verify"
kubectl wait deployment --all --for=condition=Available -n "$NAMESPACE" --timeout=600s
downtime_end=$(date +%s)
echo "quiesce -> available: $((downtime_end - downtime_start))s"

pgpod pg-client "$TARGET_PASSWORD" psql -h "$TARGET_HOST" -U cnadmin -d cantonnet \
  -c "SHOW server_version" \
  -c "SELECT datname, count(*) FROM pg_stat_activity WHERE datname <> 'cantonnet' GROUP BY 1" \
  | tee "$WORK/target-activity.txt"
grep -qE "participant" "$WORK/target-activity.txt" \
  || { echo "FAIL: no application connections on the target"; exit 1; }

idle=$(pgpod pg-client "$POSTGRES_PASSWORD" psql -h "$SOURCE_HOST" -U cnadmin -d cantonnet -tA \
  -c "SELECT count(*) FROM pg_stat_activity WHERE datname NOT IN ('cantonnet','postgres') AND datname IS NOT NULL" \
  | grep -vE '^$|^pod ')
[ "$idle" = "0" ] || { echo "FAIL: $idle connection(s) still on the source instance"; exit 1; }

echo "PASS: applications migrated to ${TARGET_HOST} (source instance idle)"
echo "manual follow-ups: wallet balance + fresh transfer; scale non-helm deployments"
echo "back up; decommission the source per step 7 of the guide once verified."
