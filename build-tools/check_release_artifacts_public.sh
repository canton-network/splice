#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

export XDG_RUNTIME_DIR="/tmp/containers"
mkdir -p "$XDG_RUNTIME_DIR"

VERSION=""
IMAGES_FILE=""
APP_CHARTS_FILE=""

DEST_DOCKER_REGISTRY="${RELEASE_DOCKER_REGISTRY}"
DEST_HELM_REGISTRY="${RELEASE_HELM_REGISTRY}"

while getopts "v:i:c:" opt; do
  case $opt in
    v) VERSION=$OPTARG ;;
    i) IMAGES_FILE=$OPTARG ;;
    c) APP_CHARTS_FILE=$OPTARG ;;
    *)
      echo "Usage: $0 -v <version> -i <images_file> -c <app_charts_file>" >&2
      exit 1
      ;;
  esac
done

if [ -z "$VERSION" ] || [ -z "$IMAGES_FILE" ] || [ -z "$APP_CHARTS_FILE" ]; then
  echo "Error: Missing required arguments." >&2
  echo "Usage: $0 -v <version> -i <images_file> -c <app_charts_file>" >&2
  exit 1
fi

IMAGES=$(<"$IMAGES_FILE")
APP_CHARTS=$(<"$APP_CHARTS_FILE")

failed=()
failure_logs=()
settings_urls=()

record_failure() {
  local artifact_type="$1"
  local name="$2"
  local target="$3"
  local output="$4"
  local url="$5"

  failed+=("${artifact_type}: ${name}")
  failure_logs+=("[$artifact_type: $name] Target: $target"$'\n'"$output")
  settings_urls+=("- ${artifact_type} '${name}': ${url}")
}

check_public() {
  local artifact_type="$1"
  local name="$2"
  local registry="$3"

  local target="docker://${registry}/${name}:${VERSION}"

  # derive org and URL-encoded package path from the GHCR registry string
  local reg_path="${registry#ghcr.io/}"
  local org="${reg_path%%/*}"
  local pkg_prefix="${reg_path#*/}"
  local encoded_pkg="${pkg_prefix//\//%2F}%2F${name}"
  local settings_url="https://github.com/orgs/${org}/packages/container/${encoded_pkg}/settings"

  local attempt=1
  local max_attempts=10
  local output=""

  echo "Verifying ${artifact_type}: ${target}"

  while [ "$attempt" -le "$max_attempts" ]; do
    local exit_code=0

    output=$(skopeo inspect --raw --no-creds "$target" 2>&1) || exit_code=$?

    if [ $exit_code -eq 0 ]; then
      echo "Successfully verified ${artifact_type} is publicly accessible: ${name}"
      return 0
    fi

    # Short-circuit on permanent access errors (401/403) to avoid wasted retry delay
    if grep -qiE 'unauthorized|denied|forbidden|manifest unknown|not found' <<<"$output"; then
      echo "Permanent access error detected (skipping retries): ${target}"
      record_failure "$artifact_type" "$name" "$target" "$output" "$settings_url"
      return 0
    fi

    echo "Attempt $attempt/$max_attempts failed for ${target} (transient error, retrying in 5s..)"
    if [ "$attempt" -eq "$max_attempts" ]; then
      record_failure "$artifact_type" "$name" "$target" "$output" "$settings_url"
      return 0
    fi

    attempt=$((attempt + 1))
    sleep 5
  done
}

echo "=== Verifying public accessibility for release version: ${VERSION} ==="

for image in $IMAGES; do
  check_public "Docker image" "$image" "$DEST_DOCKER_REGISTRY"
done

for chart in $APP_CHARTS; do
  check_public "Helm chart" "$chart" "$DEST_HELM_REGISTRY"
done

if [ ${#failed[@]} -ne 0 ]; then
  echo ""
  echo "=========================================================================================="
  echo "::error::The following package(s) are NOT publicly accessible on GHCR: $(IFS=,; echo "${failed[*]}")"
  echo "=========================================================================================="
  echo "Failure details (HTTP status / raw errors):"
  for log in "${failure_logs[@]}"; do
    echo "--------------------------------------------------"
    echo "$log"
  done
  echo "--------------------------------------------------"
  echo ""
  echo "Action required:"
  echo "  1. Open the package settings URL(s) to flip visibility from Private to Public:"
  printf '     %s\n' "${settings_urls[@]}"
  echo "  2. Re-run this GitHub Actions workflow job."
  echo "=========================================================================================="
  exit 1
fi

echo "All ${VERSION} Docker images and Helm charts are verified public."
