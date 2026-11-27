// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ClusterBasename } from '../config/gcpConfig';
import { auth0Config } from './config';

// TODO(#2873): can we get to a point where we no longer even export this outside of this file?
export const DEFAULT_AUDIENCE = 'https://canton.network.global';

export function getApiAudience(
  api: 'ledgerApi' | 'participantAdminApi' | 'validatorApi' | 'svAppApi',
  namespace: string
): string {
  // SV runbook, Validator runbook and other validators
  // TODO(#2873): extract validator namespaces from config. This requires moving it from common-validator to common.
  if (
    ['sv', 'validator', 'party-allocator', 'validator-stable-old', 'extra-validator'].includes(
      namespace
    )
  ) {
    switch (api) {
      case 'ledgerApi':
        return 'https://ledger_api.example.com';
      case 'participantAdminApi':
        return 'https://participant_admin_api.example.com';
      case 'svAppApi':
        return 'https://sv.example.com/api';
      case 'validatorApi':
        return 'https://validator.example.com/api';
    }
  }
  // Splitwell and validator1 use the default audience
  if (['splitwell', 'validator1'].includes(namespace)) {
    return DEFAULT_AUDIENCE;
  }
  // SVs
  if (api === 'participantAdminApi') {
    return `https://participant_admin_api.${namespace}.${ClusterBasename}.canton.network`;
  }
  const audienceConfig = auth0Config.audiences[api];
  switch (audienceConfig.type) {
    case 'namespaced-managed':
      switch (api) {
        case 'ledgerApi':
          return `https://ledger_api.${namespace}.${ClusterBasename}.canton.network`;
        case 'validatorApi':
          return `https://validator.${namespace}.${ClusterBasename}.canton.network/api`;
        case 'svAppApi':
          return `https://sv.${namespace}.${ClusterBasename}.canton.network/api`;
      }
      break;
    case 'unmanaged':
      return audienceConfig.audience;
  }
}
