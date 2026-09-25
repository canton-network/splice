// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Check } from "./common.ts";
import { clientIpSpoofingCheck } from "./client-ip-spoofing.ts";
import { perIpRateLimitCheck } from "./per-ip-rate-limit.ts";

export const checks: Check[] = [perIpRateLimitCheck, clientIpSpoofingCheck];
