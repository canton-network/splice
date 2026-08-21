#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Unlike the other runtime endpoints, the sequencer rate-limit caps are static
# deployment config (not fetched over the network), so this just re-serves the
# JSON it was handed via SEQUENCER_RATE_LIMITS_JSON.
main() {
  local rate_limits="${SEQUENCER_RATE_LIMITS_JSON:-}"

  if [[ -z "$rate_limits" ]]; then
    return 1
  fi

  jq -n \
    --argjson rateLimits "$rate_limits" \
    '
      {
        rateLimits: $rateLimits,
        generatedAt: (now | todate),
      }
    '
}

main "$@"
