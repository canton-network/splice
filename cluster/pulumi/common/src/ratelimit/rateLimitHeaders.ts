// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export const localRateLimitedHeader = 'x-local-rate-limit';

export const rateLimitResponseHeaders = [
  localRateLimitedHeader,
  // draft RFC headers enabled via enable_x_ratelimit_headers
  'x-ratelimit-limit',
  'x-ratelimit-remaining',
  'x-ratelimit-reset',
  // added by envoy itself on the local reply it generates when rate limiting
  'x-envoy-ratelimited',
];
