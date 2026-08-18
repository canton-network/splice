// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.google.common.util.concurrent;

/**
 * Shim that constructs a Guava {@link RateLimiter} backed by {@code SmoothBursty} with a custom
 * maximum burst duration. It lives in this package because the relevant {@code
 * SmoothRateLimiter.SmoothBursty} constructor is package-private.
 */
public final class BurstyRateLimiterFactory {

    private BurstyRateLimiterFactory() {
    }

    /**
     * Creates a bursty {@link RateLimiter} that sustains {@code permitsPerSecond} on average while
     * allowing bursts of up to {@code permitsPerSecond * maxBurstSeconds} permits after idle periods.
     */
    public static RateLimiter create(double permitsPerSecond, double maxBurstSeconds) {
        RateLimiter rateLimiter =
                new SmoothRateLimiter.SmoothBursty(
                        RateLimiter.SleepingStopwatch.createFromSystemTimer(), maxBurstSeconds);
        rateLimiter.setRate(permitsPerSecond);
        return rateLimiter;
    }
}
