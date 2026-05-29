// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

const CatchupTestThresholdsSchema = z
  .object({
    sequencerMinEventsPerSecond: z.number().positive().default(1500),
    participantMinCatchupRatio: z.number().positive().default(10),
    mediatorMinCatchupRatio: z.number().positive().default(3),
    // pick a backup at least this old
    minBackupAgeHours: z.number().positive().default(12),
    // delays under which a component is considered "caught up"
    caughtUpThresholds: z
      .object({
        sequencerBlockDelaySeconds: z.number().nonnegative().default(5),
        participantDelaySeconds: z.number().nonnegative().default(30),
        mediatorDelaySeconds: z.number().nonnegative().default(30),
      })
      .prefault({}),
  })
  .strict();

export const CatchupTestSchema = z
  .object({
    enabled: z.boolean().default(false),
    // cron expression in CronJob format; default = every Wednesday 18:00 UTC
    schedule: z.string().default('0 18 * * 3'),
    thresholds: CatchupTestThresholdsSchema.prefault({}),
  })
  .strict();
