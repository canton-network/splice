#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Resets the test environment of a CI test job before its tests are retried: a failed attempt can leave Canton
# in a state that breaks every later test (e.g. a decentralized namespace that was not reset).
# Stops Canton, moves the logs of the failed attempt to $1, drops all data from the job's Postgres service
# container, and starts Canton again with the start-canton.sh options in $2 (if $2 is not empty).

set -euo pipefail

logs_backup_dir="$1"
start_canton_options="$2"

echo "::warning::The tests failed, retrying them against a fresh Canton instance. See the job log and the artifact with the logs of the failed attempt for the failure."

./stop-canton.sh external || echo "stop-canton.sh failed, continuing"

mkdir -p log "$(dirname "$logs_backup_dir")"
# Several retries would overwrite each other's logs, we only retry once
rm -rf "$logs_backup_dir"
mv log "$logs_backup_dir"
mkdir log

export POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_USER=postgres POSTGRES_PASSWORD=postgres
# stop-canton.sh leaves the data of an external Postgres in place; the service container belongs to this job,
# so it is safe to drop everything in it
./scripts/postgres.sh external reset

if [[ -n "$start_canton_options" ]]; then
  export CANTON_DB_HOST=localhost
  # shellcheck disable=SC2086 # options are word-split on purpose
  ./start-canton.sh -p external -D $start_canton_options
  if [[ "$start_canton_options" == *"-w"* ]]; then
    ./wait-for-canton.sh -w
  elif [[ "$start_canton_options" == *"-s"* ]]; then
    ./wait-for-canton.sh -s
  fi
fi
