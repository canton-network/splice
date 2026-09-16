#!/bin/bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -Eeo pipefail

export POSTGRES_INITDB_ARGS="--data-checksums ${POSTGRES_INITDB_ARGS:-}"

docker-ensure-initdb.sh # provided by upstream image
source docker-entrypoint.sh # provided by upstream image

execute() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" "$@"
}

ENABLE_PARTICIPANT_DB_CONFLICT_CHECK="${ENABLE_PARTICIPANT_DB_CONFLICT_CHECK:-true}"

check_participant_db_conflict() {
  local db_name="$1"
  if [ "${ENABLE_PARTICIPANT_DB_CONFLICT_CHECK}" != "true" ]; then
    return 0
  fi
  if [[ "$db_name" != *participant* ]]; then
    return 0
  fi
  local existing
  existing=$(execute -tAc "SELECT datname FROM pg_database WHERE datname LIKE 'participant%'" | paste -sd ', ' -)
  if [ -n "$existing" ]; then
    echo "ERROR: Refusing to create participant database '${db_name}' because the following participant database(s) already exist: ${existing}." >&2
    echo "       A participant database should only be created during a fresh deployment. Please double-check that this is intended and not a misconfiguration." >&2
    echo "       If this is expected, disable this check by passing '-k' to start.sh." >&2
    exit 1
  fi
}

create_database_if_not_exists() {
  local db_name="$1"
  if ! execute -tc "SELECT 1 FROM pg_database WHERE datname = '$db_name'" | grep -q 1; then
    check_participant_db_conflict "$db_name"
    execute -c "CREATE DATABASE \"$db_name\""
    echo "Database '$db_name' created."
  else
    echo "Skipping '$db_name' creation since it already exists."
  fi
}

# allows to run setup scripts
docker_temp_server_start

# create a database for each CREATE_DATABASE_* env var
for var in $(compgen -v | grep '^CREATE_DATABASE_'); do
  create_database_if_not_exists "${!var}"
done
docker_temp_server_stop

# run the given command
exec "$@"
