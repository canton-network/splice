// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { config } from '@canton-network/splice-pulumi-common';
import { clusterYamlConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import util from 'node:util';
import { z } from 'zod';

export const clusterBasename = pulumi.getStack().replace(/.*[.]/, '');

export const clusterHostname = config.requireEnv('GCP_CLUSTER_HOSTNAME');
export const clusterBaseDomain = clusterHostname.split('.')[0];

export const gcpDnsProject = config.requireEnv('GCP_DNS_PROJECT');

const CloudArmorConfigSchema = z.object({
  enabled: z.boolean(),
  // "preview" is not pulumi preview, but https://cloud.google.com/armor/docs/security-policy-overview#preview_mode
  allRulesPreviewOnly: z.boolean(),
  publicEndpoints: z
    .object({})
    .catchall(
      z.object({
        rulePreviewOnly: z.boolean().default(false),
        hostname: z
          .string()
          .regex(/^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*$/, 'valid DNS hostname')
          .optional(),
        pathPrefix: z.string().regex(/^\/[^"]*$/, 'HTTP request path starting with /'),
        throttleAcrossAllEndpointsAllIps: z.object({
          withinIntervalSeconds: z.number().positive(),
          maxRequestsBeforeHttp429: z
            .number()
            .min(0, '0 to disallow requests or positive to allow'),
        }),
      })
    )
    .default({}),
});
export const InfraConfigSchema = z.object({
  infra: z.object({
    ipWhitelisting: z
      .object({
        extraWhitelistedIngress: z.array(z.string()).default([]),
        excludedIps: z.array(z.string()).default([]),
      })
      .optional(),
    enableGCReaperJob: z.boolean().default(false),
    gkeGateway: z.object({
      proxyForIstioHttp: z.boolean(),
    }),
    istio: z.object({
      enableIngressAccessLogging: z.boolean(),
      enableClusterAccessLogging: z.boolean().default(false),
      enablePublicTokenRegistry: z.boolean().default(false),
      enableGeneralIpWhitelist: z.boolean().default(false),
      istiodValues: z.object({}).catchall(z.any()).default({}),
      sequencerFlowControl: z.object({
        initialStreamWindowSize: z.int(),
        initialConnectionWindowSize: z.int(),
      }),
    }),
    extraCustomResources: z.object({}).catchall(z.any()).default({}),
  }),
  cloudArmor: CloudArmorConfigSchema,
});

export type CloudArmorConfig = z.infer<typeof CloudArmorConfigSchema>;

export type Config = z.infer<typeof InfraConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = InfraConfigSchema.parse(clusterYamlConfig);
export const enableGCReaperJob = fullConfig.infra.enableGCReaperJob;
console.error(
  `Loaded infra config: ${util.inspect(fullConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

export const infraConfig = fullConfig.infra;
export const cloudArmorConfig: CloudArmorConfig = fullConfig.cloudArmor;
