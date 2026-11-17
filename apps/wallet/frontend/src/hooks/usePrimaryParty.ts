// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useUserStatus } from './useUserStatus';

// A hook to fetch the primary party ID via the wallet userStatus API
export const usePrimaryParty = (): string | undefined => {
  const userStatusQuery = useUserStatus();
  return userStatusQuery.data?.partyId;
};
