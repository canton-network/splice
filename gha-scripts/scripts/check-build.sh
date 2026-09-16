#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -e
cd gha-scripts
npm ci
npm run build

if ! git diff --quiet || [ -n "$(git ls-files --others --exclude-standard)" ]; then
  echo "Build produced changes:"
  git status --short
  exit 1
fi
