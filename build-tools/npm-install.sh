#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

if [ -z "${CI}" ]; then
    # we rely on dependabot for npm vulnerabilities so disable the audit check here for faster installs.
    npm install --no-update-notifier --no-audit
else
  # shellcheck disable=SC2015
  for _ in {1..5}; do npm ci --no-audit && break || sleep 15; done
fi
