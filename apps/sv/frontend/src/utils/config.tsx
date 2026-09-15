// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  authSchema,
  testAuthSchema,
  serviceSchema,
  spliceInstanceNamesSchema,
  ConfigProvider,
  useConfig,
  pollIntervalSchema,
} from '@canton-network/splice-common-frontend';
import { PollingStrategy } from '@canton-network/splice-common-frontend-utils';
import React from 'react';
import { z } from 'zod';

type SvServicesConfig = {
  sv: z.infer<typeof serviceSchema>;
};

// When enabled, the SV app runs backend-less: login goes through a CIP-103
// RPC endpoint, governance reads come from Scan, and vote submissions are
// exercised on a VoteDelegation contract through the dApp API.
const alreadyTrimmed = (value: string): boolean => value === value.trim();

const dappModeEnabledSchema = z.object({
  enabled: z.literal(true),
  // Scan API base URL, e.g. http://scan.localhost:4000/api/scan
  scanUrl: z.string().min(1).refine(alreadyTrimmed, {
    message: 'must not have leading or trailing whitespace',
  }),
  // CIP-103 dApp RPC URL (wallet gateway or partner wallet), e.g. http://localhost:3030/api/v0/dapp
  cip103RpcUrl: z.string().min(1).refine(alreadyTrimmed, {
    message: 'must not have leading or trailing whitespace',
  }),
});

const dappModeDisabledSchema = z.object({
  enabled: z
    .literal(false)
    .optional()
    .transform(() => false as const),
  scanUrl: z.string().optional(),
  cip103RpcUrl: z.string().optional(),
});

export const dappModeSchema = z.union([dappModeEnabledSchema, dappModeDisabledSchema]).optional();

type SvConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
  services: SvServicesConfig;
  pollInterval?: z.infer<typeof pollIntervalSchema>;
  dappMode?: z.infer<typeof dappModeSchema>;
};

export const configScheme = z.object({
  auth: authSchema,
  testAuth: testAuthSchema.optional(),
  spliceInstanceNames: spliceInstanceNamesSchema,
  pollInterval: pollIntervalSchema,
  services: z.object({
    sv: serviceSchema,
  }),
  dappMode: dappModeSchema,
});

export const ConfigContext = React.createContext<SvConfig | undefined>(undefined);

export const SvConfigProvider: React.FC<{
  children: React.ReactNode;
}> = ({ children }) => {
  return (
    <ConfigProvider configScheme={configScheme} configContext={ConfigContext}>
      {children}
    </ConfigProvider>
  );
};

export const useSvConfig: () => SvConfig = () => useConfig<SvConfig>(ConfigContext);

export const useConfigPollInterval: () => number = () => {
  const config = useSvConfig();

  // Use default poll interval if not specified in config
  return config.pollInterval ?? PollingStrategy.FIXED;
};

/** Normalized dApp-mode settings; defined only when the mode is enabled and usable. */
export interface DappModeConfig {
  scanUrl: string;
  cip103RpcUrl: string;
}

/** Normalized view of an enabled, already-validated dappMode block. */
export const getDappModeConfig = (config: SvConfig): DappModeConfig | undefined => {
  const dappMode = config.dappMode;
  if (!dappMode?.enabled) {
    return undefined;
  }
  return {
    scanUrl: dappMode.scanUrl,
    cip103RpcUrl: dappMode.cip103RpcUrl,
  };
};

/**
 * The dApp-mode config, or undefined in standard mode. The mode is fixed for
 * the lifetime of the page (config is loaded once from window.splice_config).
 */
export const useDappModeConfig = (): DappModeConfig | undefined => getDappModeConfig(useSvConfig());
