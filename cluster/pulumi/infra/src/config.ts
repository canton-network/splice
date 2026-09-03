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

// https://cloud.google.com/armor/docs/rate-limiting-overview: intervalSec only
// accepts this fixed set of values, anything else is rejected by the GCP API.
const cloudArmorIntervalSeconds = [
  10, 30, 60, 120, 180, 240, 300, 600, 900, 1200, 1800, 2700, 3600,
];
// https://cloud.google.com/armor/docs/rate-limiting-overview: threshold count max
const cloudArmorMaxRateLimitCount = 1000000;

const CloudArmorLoggingConfigSchema = z.object({
  enabled: z.boolean().default(false),
  verboseLogging: z.boolean().default(false),
  sampleRate: z.number().min(0).max(1).default(1),
});

export type CloudArmorLoggingConfig = z.infer<typeof CloudArmorLoggingConfigSchema>;

const CloudArmorConfigSchema = z.object({
  enabled: z.boolean(),
  // "preview" is not pulumi preview, but https://cloud.google.com/armor/docs/security-policy-overview#preview_mode
  allRulesPreviewOnly: z.boolean(),
  logging: CloudArmorLoggingConfigSchema.prefault({}),
  publicEndpoints: z
    .object({})
    .catchall(
      z
        .object({
          rulePreviewOnly: z.boolean().default(false),
          // exact hostname to match; mutually exclusive with hostPrefixRegex
          hostname: z
            .string()
            .regex(/^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*$/, 'valid DNS hostname')
            .optional(),
          // expanded to `<hostPrefixRegex>.<node>.<cluster dns name>` for every DNS name
          // the cluster is served under. E.g. `scan` or `sequencer-[0-9]+`.
          // Mutually exclusive with hostname; if neither is given, any host matches.
          hostPrefixRegex: z.string().optional(),
          pathPrefix: z.string().regex(/^\/[^"]*$/, 'HTTP request path starting with /'),
          // when true, the rule only matches the subset of paths under pathPrefix that
          // are known. Must be false
          // for endpoints whose paths are not part of that config (e.g. the sequencer
          // gRPC APIs).
          restrictToRateLimitedPaths: z.boolean().default(true),
          // Per source IP throttling across all endpoints under this rule.
          //
          // Cloud Armor applies at most one rate limit per request: rules are evaluated
          // in priority order and the first match wins, and a request under the
          // threshold of a throttle rule takes its conformAction (always `allow`),
          // which ends policy evaluation. So a global (enforceOnKey: ALL) rule and a
          // per IP rule cannot both apply to the same traffic. We rate limit per IP
          // here, since that is what stops an abusive source at the edge without one
          // client being able to exhaust a bucket shared with everyone else; the global
          // cap lives in envoy instead (see sv.scan.externalRateLimits.globalLimits),
          // which is closer to the backend it protects.
          //
          // When omitted the endpoint is allowed without any Cloud Armor rate limiting.
          throttleAcrossAllEndpointsPerIp: z
            .object({
              withinIntervalSeconds: z
                .number()
                .refine(
                  n => cloudArmorIntervalSeconds.includes(n),
                  `must be one of ${cloudArmorIntervalSeconds.join(', ')}`
                ),
              maxRequestsBeforeHttp429: z
                .number()
                .int()
                .min(0, '0 to disallow requests or positive to allow')
                .max(cloudArmorMaxRateLimitCount),
            })
            .optional(),
        })
        .refine(
          e => !(e.hostname && e.hostPrefixRegex),
          'at most one of hostname and hostPrefixRegex may be set'
        )
        .refine(
          e => !(e.restrictToRateLimitedPaths && e.pathPrefix === '/'),
          'restrictToRateLimitedPaths must be false when pathPrefix is /'
        )
    )
    .default({}),
});
export const flowControlConfigSchema = z.object({
  initialStreamWindowSize: z.int(),
  initialConnectionWindowSize: z.int(),
  ports: z.array(z.number().int().positive()),
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
      flowControl: z.object({
        // public APIs like the sequencer
        public: flowControlConfigSchema,
        // internal APIs like the participant
        internal: flowControlConfigSchema,
      }),
    }),
    enableSweetSecurity: z.boolean().default(false),
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
