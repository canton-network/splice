#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

function get_digest() {
  img=$1

  img_name=$(get-docker-image-reference "$img")
  # The docker image is multi-arch, but the digests are per architecture. We support amd64 clusters only, so pick that digest.
  # timeout because we've seen this command hang for 10m
  timeout 30s docker manifest inspect "$img_name" | jq -r '.manifests[] | select(.platform.architecture=="amd64") | .digest'
}

echo "imageDigests:"
for dir in "${SPLICE_ROOT}"/cluster/images/*; do
  app=$(basename "$dir");
  if [ ! -f "$dir" ] && [ "$app" != "common" ]; then
    n=0
    MAX_RETRIES=5
    # Exponential backoff (capped at MAX_DELAY).
    BASE_DELAY=6
    MAX_DELAY=60
    # Client.Timeout from ghcr are not fun
    until [ $n -ge $MAX_RETRIES ]; do
      if ! digest=$(get_digest "$app"); then
        digest=""
      fi

      if [ -n "$digest" ]; then
        break
      fi

      n=$((n+1))
      if [ $n -ge $MAX_RETRIES ]; then
        break
      fi
      delay=$(( BASE_DELAY * (2 ** (n - 1)) ))
      delay=$(( delay < MAX_DELAY ? delay : MAX_DELAY ))
      echo "Failed to get digest for $app, attempt $n/$MAX_RETRIES. Retrying in ${delay} seconds..." >&2
      sleep "$delay"
    done

    if [ -z "$digest" ]; then
      echo "Failed to get digest for $app after $MAX_RETRIES attempts" >&2
      exit 1
    fi

    a=${app//-/_}
    echo "  $a: \"@$digest\""
  fi
done
