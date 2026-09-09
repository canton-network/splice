// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { dateTimeFormatISO, getUTCWithOffset } from '@canton-network/splice-common-frontend-utils';

export function formatDatetimeWithOffset(datetime: string | Date | dayjs.Dayjs): string {
  const d = dayjs(datetime);
  return `${d.format(dateTimeFormatISO)} (${getUTCWithOffset(d.toDate())})`;
}
