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

type AuthConfig = z.infer<typeof authSchema>;
type TestAuthConfig = z.infer<typeof testAuthSchema>;

type SvConfig = {
  // Always present in operator mode (enforced by `configScheme`); absent in dApp mode.
  auth?: AuthConfig;
  testAuth?: TestAuthConfig;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
  services: SvServicesConfig;
  pollInterval?: z.infer<typeof pollIntervalSchema>;
  dappMode?: z.infer<typeof dappModeSchema>;
};

export interface DappModeConfig {
  scanUrl: string;
}

const httpUrlSchema = z.url({ protocol: /^https?$/ });

const dappModeSchema = z
  .object({
    enabled: z.boolean().default(false),
    scanUrl: z.string().optional(),
  })
  .superRefine((d, ctx) => {
    if (!d.enabled) {
      return;
    }
    const scanUrl = d.scanUrl?.trim() ?? '';
    if (scanUrl.length === 0) {
      ctx.addIssue({
        code: 'custom',
        path: ['scanUrl'],
        message: 'dappMode.scanUrl is required when dappMode.enabled',
      });
    } else if (!httpUrlSchema.safeParse(scanUrl).success) {
      ctx.addIssue({
        code: 'custom',
        path: ['scanUrl'],
        message: 'dappMode.scanUrl must be an absolute http:// or https:// URL',
      });
    }
  })
  .optional();

const isDappModeEnabled = (cfg: { dappMode?: { enabled?: boolean } }): boolean =>
  cfg.dappMode?.enabled === true;

// `ConfigProvider` requires a `ZodObject`, so `auth`/`testAuth` are accepted as-is at the field level
// and validated by the object-level check below: required in operator mode, ignored and dropped in dApp mode.
export const configScheme = z
  .object({
    auth: z.custom<AuthConfig>().optional(),
    testAuth: z.custom<TestAuthConfig>().optional(),
    spliceInstanceNames: spliceInstanceNamesSchema,
    pollInterval: pollIntervalSchema,
    services: z.object({
      sv: serviceSchema,
    }),
    dappMode: dappModeSchema,
  })
  .superRefine((cfg, ctx) => {
    if (isDappModeEnabled(cfg)) {
      return;
    }
    if (cfg.auth === undefined) {
      ctx.addIssue({
        code: 'custom',
        path: ['auth'],
        message: 'auth is required unless dappMode.enabled',
      });
    } else {
      authSchema
        .safeParse(cfg.auth)
        .error?.issues.forEach(issue => ctx.addIssue({ ...issue, path: ['auth', ...issue.path] }));
    }
    if (cfg.testAuth !== undefined) {
      testAuthSchema
        .safeParse(cfg.testAuth)
        .error?.issues.forEach(issue =>
          ctx.addIssue({ ...issue, path: ['testAuth', ...issue.path] })
        );
    }
  })
  .overwrite(cfg => {
    if (isDappModeEnabled(cfg)) {
      return { ...cfg, auth: undefined, testAuth: undefined };
    }
    // Replace the raw values with their parsed form; on failure the check above has already reported the issues.
    const auth = authSchema.safeParse(cfg.auth);
    const testAuth = testAuthSchema.optional().safeParse(cfg.testAuth);
    return {
      ...cfg,
      auth: auth.success ? auth.data : cfg.auth,
      testAuth: testAuth.success ? testAuth.data : cfg.testAuth,
    };
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

// `configScheme` guarantees `auth` outside dApp mode, so this only narrows the type for operator-mode code.
export const requireOperatorAuth: (config: SvConfig) => AuthConfig = config => {
  if (config.auth === undefined) {
    throw new Error('auth config is required in operator mode');
  }
  return config.auth;
};

// Returns undefined in operator mode.
export const getDappModeConfig: (config: SvConfig) => DappModeConfig | undefined = config => {
  const dappMode = config.dappMode;
  if (!dappMode?.enabled || dappMode.scanUrl === undefined) {
    return undefined;
  }
  return { scanUrl: dappMode.scanUrl.trim().replace(/\/+$/, '') };
};

export const useDappModeConfig: () => DappModeConfig | undefined = () =>
  getDappModeConfig(useSvConfig());

export const useConfigPollInterval: () => number = () => {
  const config = useSvConfig();

  // Use default poll interval if not specified in config
  return config.pollInterval ?? PollingStrategy.FIXED;
};
