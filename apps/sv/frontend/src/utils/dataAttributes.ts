// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { HTMLAttributes } from 'react';

export const dataTestId = (testId: string): HTMLAttributes<HTMLElement> =>
  ({ 'data-testid': testId }) as HTMLAttributes<HTMLElement>;
