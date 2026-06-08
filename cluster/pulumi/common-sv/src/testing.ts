// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

const CatchupTestThresholdsSchema = z
  .object({
    sequencerMinEventsPerSecond: z.number().positive(),
    participantMinEventsPerSecond: z.number().positive(),
    mediatorMinEventsPerSecond: z.number().positive(),
    // delays under which a component is considered "caught up"
    sequencerBlockDelaySeconds: z.number().nonnegative(),
    participantDelaySeconds: z.number().nonnegative(),
    mediatorDelaySeconds: z.number().nonnegative(),
  })
  .strict();

export const CatchupTestSchema = z
  .object({
    enabled: z.boolean().default(false),
    thresholds: CatchupTestThresholdsSchema,
  })
  .strict();
