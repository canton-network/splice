#!/usr/bin/env bash

# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# standardizes the flags we use for zizmor

zizmor \
  --offline \
  --strict-collection \
  --show-audit-urls=always \
  .github/ gha-scripts/actions/
