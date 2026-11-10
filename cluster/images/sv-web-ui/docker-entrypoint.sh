#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

unsafe=${SPLICE_APP_UI_UNSAFE:-false}
http=${SPLICE_APP_UI_HTTP_URL:-false}
dapp_mode_enabled=${SPLICE_APP_UI_DAPP_MODE_ENABLED:-}
export SPLICE_APP_UI_DAPP_MODE_SCAN_URL=${SPLICE_APP_UI_DAPP_MODE_SCAN_URL:-}

# Substituted unquoted into config.js, so it must be exactly `true` or `false`.
case "$dapp_mode_enabled" in
  true|1) dapp_mode_enabled=true ;;
  false|0|"") dapp_mode_enabled=false ;;
  *)
    echo "ERROR: SPLICE_APP_UI_DAPP_MODE_ENABLED must be true or false (got '$dapp_mode_enabled')" >&2
    exit 1
    ;;
esac
if [ "$dapp_mode_enabled" == "true" ] && [ -z "$SPLICE_APP_UI_DAPP_MODE_SCAN_URL" ]; then
  echo "ERROR: SPLICE_APP_UI_DAPP_MODE_SCAN_URL must be set when SPLICE_APP_UI_DAPP_MODE_ENABLED is true" >&2
  exit 1
fi
export SPLICE_APP_UI_DAPP_MODE_ENABLED=$dapp_mode_enabled

echo "Generating config.js file..."

if [ "$http" == "true" ] || [ "$http" == "1" ]; then
  echo "WARNING: Using an http url"
  sed -i 's/url: "https:\/\/"/url: "http:\/\/"/' /tmpl/config.js.tmpl
fi

if [ "$unsafe" == "true" ] || [ "$unsafe" == "1" ]; then
  echo "WARNING: Running in unsafe mode"
  sed -i 's/algorithm: "rs-256"/algorithm: "hs-256-unsafe"/' /tmpl/config.js.tmpl
  # shellcheck disable=SC2016
  sed -i 's/client_id: .*/secret: "${SPLICE_APP_UI_UNSAFE_SECRET}",/' /tmpl/config.js.tmpl
  sed -i 's/authority: .*//' /tmpl/config.js.tmpl
  # shellcheck disable=SC2016
  envsubst '$SPLICE_APP_UI_AUTH_AUDIENCE,$SPLICE_APP_UI_UNSAFE_SECRET,$SPLICE_APP_UI_NETWORK_NAME,$SPLICE_APP_UI_AMULET_NAME,$SPLICE_APP_UI_AMULET_NAME_ACRONYM,$SPLICE_APP_UI_NAME_SERVICE_NAME,$SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM,$SPLICE_APP_UI_NETWORK_FAVICON_URL,$SPLICE_APP_UI_POLL_INTERVAL,$SPLICE_APP_UI_DAPP_MODE_ENABLED,$SPLICE_APP_UI_DAPP_MODE_SCAN_URL' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js
else
  # shellcheck disable=SC2016
  envsubst '$SPLICE_APP_UI_AUTH_CLIENT_ID,$SPLICE_APP_UI_AUTH_URL,$SPLICE_APP_UI_AUTH_AUDIENCE,$SPLICE_APP_UI_NETWORK_NAME,$SPLICE_APP_UI_AMULET_NAME,$SPLICE_APP_UI_AMULET_NAME_ACRONYM,$SPLICE_APP_UI_NAME_SERVICE_NAME,$SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM,$SPLICE_APP_UI_NETWORK_FAVICON_URL,$SPLICE_APP_UI_POLL_INTERVAL,$SPLICE_APP_UI_DAPP_MODE_ENABLED,$SPLICE_APP_UI_DAPP_MODE_SCAN_URL' < /tmpl/config.js.tmpl > /usr/share/nginx/html/config.js
fi

echo "Starting nginx"
exec /docker-entrypoint.sh nginx -g 'daemon off;'
