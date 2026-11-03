#!/bin/bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

check() {
  echo "Checking $1"
  # -Y off: busybox wget ignores no_proxy, so a proxy injected via the environment would break the probe
  wget -Y off -q --tries=1 --timeout=5 -O /dev/null "http://127.0.0.1:$1/health/ready"
}

if [ "$APP_USER_PROFILE" = "on" ]; then
  check "2${CANTON_HTTP_HEALTHCHECK_PORT_SUFFIX}"
fi
if [ "$APP_PROVIDER_PROFILE" = "on" ]; then
  check "3${CANTON_HTTP_HEALTHCHECK_PORT_SUFFIX}"
fi
if [ "$SV_PROFILE" = "on" ]; then
  check "4${CANTON_HTTP_HEALTHCHECK_PORT_SUFFIX}"
fi
