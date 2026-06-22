#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

SCAN_URL="${SCAN_URL:-http://scan-app:5012}"

CURL_TIMEOUT="${CURL_TIMEOUT:-15}"

TLS_SKIP_VERIFY="${TLS_SKIP_VERIFY:-false}"
CURL_CMD=(curl -fs -m "$CURL_TIMEOUT")
[[ $TLS_SKIP_VERIFY == true ]] && CURL_CMD+=(-k)

get_serial_id() {
  local fetched_serial_id; fetched_serial_id=$("${CURL_CMD[@]}" "$SCAN_URL/api/scan/v0/active-synchronizer-serial" | jq -r '.serial') || true

  if [[ -n "$fetched_serial_id" ]]; then
    echo "$fetched_serial_id"
  else
    echo null
  fi
}

main() {
  local active_synchronizer_serial; active_synchronizer_serial=$(get_serial_id)

  jq -n \
    --argjson active_synchronizer_serial "$active_synchronizer_serial" \
    '
      {
        runtimeInfo: {
          activeSynchronizerSerial: {
            value: $active_synchronizer_serial,
            description: "The current physical synchronizer serial as reported by the SV participant. This value is fetched from Scan and is null if it cannot be fetched.",
          },
        },
        generatedAt: (now | todate),
      }
    '
}

main "$@"
