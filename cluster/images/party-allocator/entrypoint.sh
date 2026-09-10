#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# env subst just ignores unset variables so we always substitute for both fixed tokens and non-fixed tokens.
# shellcheck disable=SC2016
EXTERNAL_CONFIG="$(echo "$EXTERNAL_CONFIG" | envsubst '$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_TOKEN,$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME,$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_URL,$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_CLIENT_ID,$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_CLIENT_SECRET,$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_AUDIENCE,$SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_SCOPE')"
export EXTERNAL_CONFIG

exec node --enable-source-maps party-allocator/bundle.js
