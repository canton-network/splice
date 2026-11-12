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

const httpUrlSchema = z.url({
  protocol: /^https?$/,
  error: 'dappMode.scanUrl must be an absolute http:// or https:// URL',
});

const scanUrlRequired = 'dappMode.scanUrl is required when dappMode.enabled';

// Normalised once here (trimmed, trailing slashes stripped) so consumers can use it as a base URL directly.
const scanUrlSchema = z
  .string({ error: scanUrlRequired })
  .trim()
  .min(1, { error: scanUrlRequired })
  .pipe(httpUrlSchema)
  .transform(url => url.replace(/\/+$/, ''));

const commonConfigSchema = z.object({
  spliceInstanceNames: spliceInstanceNamesSchema,
  pollInterval: pollIntervalSchema,
});

// Operator mode: logs in against the SV app. A `dappMode` block with `enabled: false` (or none) selects it;
// any other `dappMode` fields are ignored.
const operatorModeSchema = z.object({
  services: z.object({
    sv: serviceSchema,
  }),
  auth: authSchema,
  testAuth: testAuthSchema.optional(),
  dappMode: z
    .object({
      enabled: z
        .literal(false, {
          error: 'dappMode.enabled must be true (dApp mode) or false (operator mode)',
        })
        .default(false),
    })
    .optional(),
});

// dApp mode: read-only, no login, no SV app; governance is read from Scan. `services`, `auth` and `testAuth`
// are ignored and dropped.
const dappModeSchema = z.object({
  dappMode: z.object({
    enabled: z.literal(true),
    scanUrl: scanUrlSchema,
  }),
});

const authRequired =
  'auth is required in operator mode; for the read-only dApp mode set dappMode.enabled: true and dappMode.scanUrl instead';

const isDappModeSelected = (input: unknown): boolean => {
  const dappMode =
    typeof input === 'object' && input !== null ? Reflect.get(input, 'dappMode') : undefined;
  return (
    typeof dappMode === 'object' && dappMode !== null && Reflect.get(dappMode, 'enabled') === true
  );
};

// The mode is picked from `dappMode.enabled` (exactly `true` selects dApp mode) and only that mode's fields are
// validated. A plain z.union would hide e.g. a bad auth block or a missing scanUrl behind a generic "matches
// neither variant" error, because the other variant always fails too.
const modeConfigSchema = z
  .unknown()
  .transform(
    (input, ctx): z.output<typeof operatorModeSchema> | z.output<typeof dappModeSchema> => {
      const result = isDappModeSelected(input)
        ? dappModeSchema.safeParse(input)
        : operatorModeSchema.safeParse(input, {
            error: issue =>
              issue.path?.length === 1 && issue.path[0] === 'auth' && issue.input === undefined
                ? authRequired
                : undefined,
          });
      if (result.success) {
        return result.data;
      }
      result.error.issues.forEach(issue => ctx.addIssue({ ...issue }));
      return z.NEVER;
    }
  );

export const configScheme = commonConfigSchema.and(modeConfigSchema);

export type SvConfig = z.infer<typeof configScheme>;
export type OperatorSvConfig = z.infer<typeof commonConfigSchema> &
  z.infer<typeof operatorModeSchema>;
export type DappModeConfig = z.infer<typeof dappModeSchema>['dappMode'];

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

// Narrows the config to operator mode; throws in dApp mode, where there is no auth or SV app config.
export const requireOperatorConfig: (config: SvConfig) => OperatorSvConfig = config => {
  if (!('auth' in config)) {
    throw new Error('auth config is required in operator mode');
  }
  return config;
};

// Returns the parsed `dappMode` block itself (a stable reference), or undefined in operator mode.
export const getDappModeConfig: (config: SvConfig) => DappModeConfig | undefined = config =>
  config.dappMode?.enabled ? config.dappMode : undefined;

export const useOperatorConfig: () => OperatorSvConfig = () => requireOperatorConfig(useSvConfig());

export const useDappModeConfig: () => DappModeConfig | undefined = () =>
  getDappModeConfig(useSvConfig());

export const useConfigPollInterval: () => number = () => {
  const config = useSvConfig();

  // Use default poll interval if not specified in config
  return config.pollInterval ?? PollingStrategy.FIXED;
};
