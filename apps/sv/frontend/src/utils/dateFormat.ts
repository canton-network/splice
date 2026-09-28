// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';

export const formatDatetimeWithOffset = (d: dayjs.ConfigType): string => {
  const date = dayjs(d);
  return date.isValid() ? date.format(`${dateTimeFormatISO} [(UTC]Z[)]`) : '';
};
