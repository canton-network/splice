// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export const localRateLimitedHeader = 'x-local-rate-limit';

/**
 * Response headers that the local rate limit filter adds on the inbound sidecar of a rate
 * limited app.
 *
 * They are the only way to attribute a rejection to a specific limit (envoy emits neither
 * per-descriptor stats nor dynamic metadata for local rate limits), so we let the sidecar
 * add them and log them in the sidecar access log, but we strip them again on the ingress
 * gateway so that they are never exposed to clients.
 */
export const rateLimitResponseHeaders = [
  localRateLimitedHeader,
  // draft RFC headers enabled via enable_x_ratelimit_headers
  'x-ratelimit-limit',
  'x-ratelimit-remaining',
  'x-ratelimit-reset',
  // added by envoy itself on the local reply it generates when rate limiting
  'x-envoy-ratelimited',
];
