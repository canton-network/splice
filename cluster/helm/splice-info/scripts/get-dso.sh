#!/usr/bin/env bash

set -euo pipefail

CURL_TIMEOUT="${CURL_TIMEOUT:-15}"

TLS_SKIP_VERIFY="${TLS_SKIP_VERIFY:-false}"
CURL_CMD=(curl -fs -m "$CURL_TIMEOUT")
[[ $TLS_SKIP_VERIFY == true ]] && CURL_CMD+=(-k)

result=$("${CURL_CMD[@]}" "$SCAN_URL/api/scan/v0/dso") || exit 1
echo "$result"
