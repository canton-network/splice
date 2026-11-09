// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import BigNumber from 'bignumber.js';

export const parseBigNumber = (text: string): BigNumber => {
  try {
    return new BigNumber(text);
  } catch {
    return new BigNumber(NaN);
  }
};
