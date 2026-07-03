// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterSubConfig } from '@canton-network/splice-pulumi-common';
import { z } from 'zod';

const quotaMetricNameSchema = z
  .string()
  .regex(/^[a-zA-Z0-9.-]+\.[a-zA-Z0-9-]+\/[a-zA-Z0-9_./-]+$/, 'full GCP quota metric name');

const GcpQuotasConfigSchema = z.object({
  // so existing overrides don't break
  enabled: z.literal(true).optional(),
  excludedMetrics: z.array(quotaMetricNameSchema),
  excludedApproachingMetrics: z.array(quotaMetricNameSchema),
  rollingWindowSeconds: z.number().int().min(60, {
    // this rule comes from GCP aggregations.alignmentPeriod on quota-exceeded
    message: 'must be at least 60s',
  }),
  retestWindowSeconds: z
    .number()
    .int()
    .positive()
    .refine(v => v % 60 === 0, {
      // this rule comes from GCP duration on quota-exceeded
      message: 'must be a positive multiple of 60s',
    }),
});

const MonitoringConfigSchema = z
  .object({
    enableGrafanaServiceAccountToken: z.boolean(),
    alerting: z.object({
      enableNoDataAlerts: z.boolean(),
      alerts: z.object({
        pruning: z.object({
          participantRetentionDays: z.number(),
          sequencerRetentionDays: z.number(),
          mediatorRetentionDays: z.number(),
        }),
        ingestion: z.object({
          thresholdEntriesPerBatch: z.number(),
        }),
        delegatelessContention: z.object({
          thresholdPerNamespace: z.number(),
        }),
        delegatelessNonLocalContention: z.object({
          thresholdPerNamespace: z.number(),
        }),
        trafficWaste: z.object({
          kilobytes: z.number(),
          overMinutes: z.number(),
          quantile: z.number(),
        }),
        confirmationRequests: z.object({
          total: z.object({
            rate: z.number(),
            overMinutes: z.number(),
          }),
        }),
        dsoMissedConfirmations: z.object({
          // Fraction (0-1) of confirmation requests (as measured by the mediator) in
          // which the DSO party may miss its confirmation within the rolling window
          // before the alert fires.
          threshold: z.number(),
          // Rolling window (in minutes) over which the DSO party missed confirmation
          // rate is computed.
          windowMinutes: z.number(),
        }),
        cloudSql: z.object({
          maintenance: z.boolean(),
        }),
        cometbft: z.object({
          expectedMaxBlocksPerSecond: z.number(),
        }),
        loadTester: z.object({
          minRate: z.number(),
        }),
        svNames: z.array(z.string()).default([]),
        mediators: z.object({
          acknowledgementLagSeconds: z.number(),
        }),
        deployment: z.object({
          pendingPeriodMinutes: z.number(),
        }),
        sequencerClientDelay: z.object({
          seconds: z.number(),
        }),
        acsCommitments: z.object({
          checkpointDelay: z.object({
            seconds: z.number(),
          }),
          completedDelay: z.object({
            seconds: z.number(),
          }),
          computeDuration: z.object({
            seconds: z.number(),
          }),
        }),
        acsSnapshots: z.object({
          saveLatencyThresholdSeconds: z.number(),
          updateLatencyThresholdSeconds: z.number(),
        }),
        sequencerRateLimits: z.object({
          rejectionRateThreshold: z.number(),
          circuitBreakerStateThreshold: z.number(),
        }),
        scanConnectionDisagreement: z.object({
          // Fraction (0-1) of BFT consensus comparisons on a scan connection that may
          // return a response (successful or failed) disagreeing with the consensus
          // result before the success/failure alerts fire.
          alertThreshold: z.number(),
          // Requests (by their `request` label) to exclude from the scan connection
          // disagreement alerts. Matched as a regex against the `request` label.
          excludedRequests: z.array(z.string()).default([]),
          // Scan connections (by their `scan_connection` label) to exclude from the scan
          // connection disagreement alerts. Matched as a regex against the
          // `scan_connection` label.
          excludedConnections: z.array(z.string()).default([]),
        }),
        walletSweep: z.object({
          tolerance: z.number(),
        }),
        gcpQuotas: GcpQuotasConfigSchema,
        natPortUsage: z
          .object({
            thresholdPercent: z.number().min(0).max(100),
          })
          .default({ thresholdPercent: 80 }),
        trafficBasedRewards: z.object({
          featuredAppRightsLimit: z.number(),
        }),
      }),
      logAlerts: z.object({}).catchall(z.string()).default({}),
      loggedSecretsFilter: z.string().optional(),
      muteTimeIntervals: z
        .array(
          z.object({
            name: z.string(),
            objectMatchers: z.array(z.tuple([z.string(), z.string(), z.string()])),
            startTime: z.string(), // UTC
            endTime: z.string(), // UTC
            weekdays: z.array(z.string()).optional(), // e.g. ['monday', 'tuesday:friday']
          })
        )
        .default([]),
    }),
  })
  .strict();

export const monitoringConfig = MonitoringConfigSchema.parse(clusterSubConfig('monitoring'));

export type GcpQuotaAlertsConfig = z.infer<typeof GcpQuotasConfigSchema>;

const NatPortUsageConfigSchema = z.object({
  thresholdPercent: z.number().min(0).max(100),
});

export type NatPortUsageConfig = z.infer<typeof NatPortUsageConfigSchema>;

const PrometheusConfigSchema = z.object({
  prometheus: z.object({
    storageSize: z.string(),
    retentionDuration: z.string(),
    retentionSize: z.string(),
    installPrometheusPushgateway: z.boolean().default(false),
  }),
});

export const prometheusConfig = PrometheusConfigSchema.parse(clusterSubConfig('infra')).prometheus;
