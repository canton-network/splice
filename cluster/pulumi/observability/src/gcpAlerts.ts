// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import {
  CLOUD_ARMOR_POLICY_NAME,
  CLOUD_ARMOR_WAF_RULE_MAX_PRIORITY,
  CLOUD_ARMOR_WAF_RULE_MIN_PRIORITY,
  CLUSTER_BASENAME,
  CLUSTER_NAME,
  conditionalString,
  config,
} from '@canton-network/splice-pulumi-common';

import { slackAlertNotificationChannel, slackToken } from './alertings';
import {
  type CloudArmorAlertsConfig,
  type CloudArmorConfig,
  type GcpQuotaAlertsConfig,
  monitoringConfig,
  type NatPortUsageConfig,
} from './config';

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

function ensureTrailingNewline(s: string): string {
  return s.endsWith('\n') ? s : `${s}\n`;
}

// Monitoring filter limited to 2048 chars: https://cloud.google.com/monitoring/api/v3/filters
function assertFilterLength(filter: string): string {
  if (filter.length > 2048) {
    throw new Error(
      `${CLUSTER_BASENAME} monitoring filter is ${filter.length} chars; >2048 char limit`
    );
  }
  return filter;
}

export function getNotificationChannel(
  name: string = `${CLUSTER_BASENAME} Slack Alert Notification Channel`
): gcp.monitoring.NotificationChannel | undefined {
  const channelSlackName =
    slackAlertNotificationChannel &&
    config.requireEnv('SLACK_ALERT_NOTIFICATION_CHANNEL_FULL_NAME');
  return channelSlackName
    ? new gcp.monitoring.NotificationChannel(channelSlackName, {
        displayName: name,
        type: 'slack',
        labels: {
          channel_name: `#${channelSlackName}`,
        },
        sensitiveLabels: {
          authToken: slackToken(),
        },
      })
    : undefined;
}

function getAlertStrategy(notificationChannel: gcp.monitoring.NotificationChannel) {
  return {
    autoClose: '3600s',
    notificationChannelStrategies: [
      {
        notificationChannelNames: [notificationChannel.name],
        renotifyInterval: `${4 * 60 * 60}s`, // 4 hours
      },
    ],
  };
}

type AlertPolicyBaseArgs = Pick<
  gcp.monitoring.AlertPolicyArgs,
  'alertStrategy' | 'combiner' | 'notificationChannels' | 'userLabels'
>;

// Arguments shared by all our alert policies: same notification channel, same
// strategy, and the cluster label that alert routing filters on.
function getAlertPolicyBaseArgs(
  notificationChannel: gcp.monitoring.NotificationChannel
): AlertPolicyBaseArgs {
  return {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    notificationChannels: [notificationChannel.name],
    userLabels: { cluster: CLUSTER_BASENAME },
    // severity: 'SEVERITY_UNSPECIFIED', // "Policy Severity Level"
  };
}

export function installGcpLoggingAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const logAlerts = monitoringConfig.alerting.logAlerts;
  const logAlertsString = `resource.labels.cluster_name="${CLUSTER_NAME}"
${Object.keys(logAlerts)
  .sort()
  .map(k => ensureTrailingNewline(logAlerts[k]))
  .join('')}`;
  if (logAlertsString.length > 20000) {
    // LQL limited to 20k: https://cloud.google.com/logging/quotas#log-based-metrics
    throw new Error(
      `${CLUSTER_BASENAME} log alerts string is ${logAlertsString.length} chars; >20000 char limit`
    );
  }

  const logWarningsMetric = new gcp.logging.Metric('log_warnings', {
    name: `log_warnings_${CLUSTER_BASENAME}`,
    description: 'Logs with a severity level of warning or above',
    filter: logAlertsString,
    labelExtractors: {
      cluster: 'EXTRACT(resource.labels.cluster_name)',
      namespace: 'EXTRACT(resource.labels.namespace_name)',
    },
    metricDescriptor: {
      labels: [
        {
          description: 'Pod namespace',
          key: 'namespace',
        },
        {
          description: 'Cluster name',
          key: 'cluster',
        },
      ],
      metricKind: 'DELTA',
      valueType: 'INT64',
    },
  });

  const alertCount = enableChaosMesh ? 50 : 1;
  const displayName = `Log warnings and errors > ${alertCount} ${CLUSTER_BASENAME}`;
  new gcp.monitoring.AlertPolicy('logsAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    conditions: [
      {
        conditionThreshold: {
          aggregations: [
            {
              //query period
              // if the chaos mesh is enabled we expand the query period to 1 hour to avoid false positives when the mesh is running
              alignmentPeriod: enableChaosMesh ? '3600s' : '600s',
              crossSeriesReducer: 'REDUCE_SUM',
              groupByFields: ['metric.label.cluster'],
              perSeriesAligner: 'ALIGN_SUM',
            },
          ],
          comparison: 'COMPARISON_GT',
          //retest period
          duration: '300s',
          filter: pulumi.interpolate`resource.type="k8s_container" ${conditionalString(
            enableChaosMesh,
            'AND resource.labels.namespace_name != "sv-4" '
          )} AND metric.type = "logging.googleapis.com/user/${logWarningsMetric.name}"`,
          trigger: {
            count: alertCount,
          },
        },
        displayName: displayName,
      },
    ],
    displayName: displayName,
    notificationChannels: [notificationChannel.name],
  });
}

export function installLoggedSecretsAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const loggedSecretsFilter = monitoringConfig.alerting.loggedSecretsFilter!;
  const filter = `resource.labels.cluster_name="${CLUSTER_NAME}"
${ensureTrailingNewline(loggedSecretsFilter)}`;

  const loggedSecretsMetric = new gcp.logging.Metric('logged_secrets', {
    name: `logged_secrets_${CLUSTER_BASENAME}`,
    description: 'Logs containing secrets (JWTs, Bearer tokens, passwords, etc.)',
    filter: filter,
    labelExtractors: {
      cluster: 'EXTRACT(resource.labels.cluster_name)',
      namespace: 'EXTRACT(resource.labels.namespace_name)',
    },
    metricDescriptor: {
      labels: [
        {
          description: 'Pod namespace',
          key: 'namespace',
        },
        {
          description: 'Cluster name',
          key: 'cluster',
        },
      ],
      metricKind: 'DELTA',
      valueType: 'INT64',
    },
  });

  const displayName = `Logged secrets detected in ${CLUSTER_BASENAME}`;
  new gcp.monitoring.AlertPolicy('loggedSecretsAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    conditions: [
      {
        conditionThreshold: {
          aggregations: [
            {
              //query period
              alignmentPeriod: '600s',
              crossSeriesReducer: 'REDUCE_SUM',
              groupByFields: ['metric.label.cluster'],
              perSeriesAligner: 'ALIGN_SUM',
            },
          ],
          comparison: 'COMPARISON_GT',
          // No retest period -- any secret in logs is critical
          duration: '0s',
          filter: pulumi.interpolate`resource.type="k8s_container" AND metric.type = "logging.googleapis.com/user/${loggedSecretsMetric.name}"`,
          trigger: {
            count: 1,
          },
        },
        displayName: displayName,
      },
    ],
    displayName: displayName,
    notificationChannels: [notificationChannel.name],
  });
}

// https://cloud.google.com/kubernetes-engine/docs/concepts/cluster-upgrades#control_plane_upgrade_logs
export function installClusterMaintenanceUpdateAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const logGkeClusterUpdate = new gcp.logging.Metric('log_gke_cluster_update', {
    name: `log_gke_cluster_update_${CLUSTER_BASENAME}`,
    description: 'Logs with ClusterUpdate events',
    filter: `
resource.labels.cluster_name="${CLUSTER_NAME}"
resource.type=~"(gke_cluster|gke_nodepool)"
jsonPayload.@type=~"UpgradeEvent"`,
    labelExtractors: {
      cluster: 'EXTRACT(resource.labels.cluster_name)',
    },
    metricDescriptor: {
      labels: [
        {
          description: 'Cluster name',
          key: 'cluster',
        },
      ],
      metricKind: 'DELTA',
      valueType: 'INT64',
    },
  });

  const displayName = `Cluster ${CLUSTER_BASENAME} is being updated`;
  new gcp.monitoring.AlertPolicy('updateClusterAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    conditions: [
      {
        conditionThreshold: {
          aggregations: [
            {
              //query period
              alignmentPeriod: '600s',
              crossSeriesReducer: 'REDUCE_SUM',
              groupByFields: ['metric.label.cluster'],
              perSeriesAligner: 'ALIGN_SUM',
            },
          ],
          comparison: 'COMPARISON_GT',
          //retest period
          duration: '60s',
          filter: pulumi.interpolate`resource.type="global" AND metric.type = "logging.googleapis.com/user/${logGkeClusterUpdate.name}"`,
          trigger: {
            count: 1,
          },
        },
        displayName: displayName,
      },
    ],
    displayName: displayName,
    notificationChannels: [notificationChannel.name],
  });
}

export function installCloudSQLMaintenanceUpdateAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  const logGkeCloudSQLUpdate = new gcp.logging.Metric('log_gke_cloudsql_update', {
    name: `log_gke_cloudsql_update_${CLUSTER_BASENAME}`,
    description: 'Logs with cloudsql databases events',
    filter: `
resource.type="cloudsql_database"
"terminating connection due to administrator command" OR "the database system is shutting down"`,
  });

  const displayName = `Possible CloudSQL maintenance going on in ${CLUSTER_BASENAME}`;
  new gcp.monitoring.AlertPolicy('updateCloudSQLAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    conditions: [
      {
        conditionThreshold: {
          aggregations: [
            {
              //query period
              alignmentPeriod: '600s',
              crossSeriesReducer: 'REDUCE_SUM',
              perSeriesAligner: 'ALIGN_SUM',
            },
          ],
          comparison: 'COMPARISON_GT',
          //retest period
          duration: '60s',
          filter: pulumi.interpolate`resource.type="cloudsql_database" AND metric.type = "logging.googleapis.com/user/${logGkeCloudSQLUpdate.name}"`,
          trigger: {
            count: 1,
          },
        },
        displayName: displayName,
      },
    ],
    displayName: displayName,
    notificationChannels: [notificationChannel.name],
  });
}

export function installGcpQuotaAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel,
  gcpQuotasConfig: GcpQuotaAlertsConfig
): void {
  const quotaUsageThreshold = 0.9;
  const quotaUsageThresholdPercent = quotaUsageThreshold * 100;
  const excludedMetrics = gcpQuotasConfig.excludedMetrics;

  // Build exclusion fragments for threshold filters and PromQL queries.
  // excludedMetrics applies to all alerts; excludedApproachingMetrics only to the >90% alerts.
  // Cloud Monitoring filters only support = and != (no regex), so we use one != per metric.
  const thresholdExclusion = excludedMetrics
    .map(m => ` AND metric.label.quota_metric != "${m}"`)
    .join('');

  const approachingExcluded = [...excludedMetrics, ...gcpQuotasConfig.excludedApproachingMetrics];
  const approachingExclusionRegex =
    approachingExcluded.length > 0 ? approachingExcluded.join('|') : null;
  const promqlExclusion =
    approachingExclusionRegex !== null ? `, quota_metric!~"${approachingExclusionRegex}"` : '';

  const { rollingWindowSeconds, retestWindowSeconds } = gcpQuotasConfig;
  const rollingWindowDuration = `${rollingWindowSeconds}s`;
  const retestWindowDuration = `${retestWindowSeconds}s`;

  const baseArgs = getAlertPolicyBaseArgs(notificationChannel);

  new gcp.monitoring.AlertPolicy('quotaExceededAlert', {
    ...baseArgs,
    displayName: `Quota Exceeded in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `Quota \${metric.label.quota_metric} exceeded in ${CLUSTER_BASENAME}`,
      content: `The quota "\${metric.display_name}" (\${metric.label.quota_metric}) has been exceeded in cluster ${CLUSTER_BASENAME}.`,
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        // "Quota Full" (Exceeded right now)
        displayName: `Quota Exceeded in ${CLUSTER_BASENAME}`,
        conditionThreshold: {
          aggregations: [
            {
              alignmentPeriod: rollingWindowDuration, // "Rolling window"
              crossSeriesReducer: 'REDUCE_SUM',
              groupByFields: ['metric.label.quota_metric'],
              perSeriesAligner: 'ALIGN_COUNT_TRUE',
            },
          ],
          comparison: 'COMPARISON_GT',
          duration: retestWindowDuration, // "Retest window"
          filter: assertFilterLength(
            `resource.type="consumer_quota" AND metric.type="serviceruntime.googleapis.com/quota/exceeded"${thresholdExclusion}`
          ),
          trigger: {
            count: 1,
          },
        },
      },
    ],
  });

  // Allocation quotas track consumed capacity (for example CPUs, IPs, disk)
  // against fixed limits, while rate quotas track request throughput over time
  // windows (for example API calls per minute). These are tracked separately so
  // we have separate alerts for them

  const windowSettings: Pick<
    gcp.types.input.monitoring.AlertPolicyConditionConditionPrometheusQueryLanguage,
    'duration' | 'evaluationInterval'
  > = {
    duration: retestWindowDuration, // "Retest window"
    evaluationInterval: '30s', // "Evaluation interval"
  };

  new gcp.monitoring.AlertPolicy('quotaAllocationAlert', {
    ...baseArgs,
    displayName: `Allocation Quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `Allocation quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
      content: [
        `An allocation quota (CPUs, Static IPs, Disk Space, etc.) is >${quotaUsageThresholdPercent}% utilized in **${CLUSTER_BASENAME}**.`,
        'Check the incident details, chart under "Alert Metrics", for the specific quota.',
      ].join('\n\n'),
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        // Tracks resources like CPUs, Static IPs, Disk Space
        displayName: `Allocation Quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
        conditionPrometheusQueryLanguage: {
          query: `
            avg_over_time(serviceruntime_googleapis_com:quota_allocation_usage{monitored_resource="consumer_quota"${promqlExclusion}}[${rollingWindowDuration}])
            / ignoring(limit_name) group_right()
            (serviceruntime_googleapis_com:quota_limit{monitored_resource="consumer_quota"${promqlExclusion}} > 0)
            > ${quotaUsageThreshold}
          `,
          ...windowSettings,
        },
      },
    ],
  });

  new gcp.monitoring.AlertPolicy('quotaRateAlert', {
    ...baseArgs,
    displayName: `Rate Quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `Rate quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
      content: [
        `A rate quota (API requests per minute, HSM operations, etc.) is >${quotaUsageThresholdPercent}% utilized in **${CLUSTER_BASENAME}**.`,
        'Check the incident details, chart under "Alert Metrics", for the specific quota.',
      ].join('\n\n'),
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        // Tracks API requests, HSM operations per minute, etc.
        displayName: `Rate Quota approaching limit (>${quotaUsageThresholdPercent}%) in ${CLUSTER_BASENAME}`,
        conditionPrometheusQueryLanguage: {
          query: `
            avg_over_time(serviceruntime_googleapis_com:quota_rate_net_usage{monitored_resource="consumer_quota"${promqlExclusion}}[${rollingWindowDuration}])
            / ignoring(limit_name) group_right()
            (serviceruntime_googleapis_com:quota_limit{monitored_resource="consumer_quota"${promqlExclusion}} > 0)
            > ${quotaUsageThreshold}
          `,
          ...windowSettings,
        },
      },
    ],
  });
}

export function installNatAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel,
  natConfig: NatPortUsageConfig
): void {
  const baseArgs = getAlertPolicyBaseArgs(notificationChannel);

  const prometheusDefaults = {
    duration: '0s',
    evaluationInterval: '30s',
  };

  new gcp.monitoring.AlertPolicy('natAllocationFailedAlert', {
    ...baseArgs,
    displayName: `NAT allocation failed in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `NAT allocation failed in ${CLUSTER_BASENAME}`,
      content: `Cloud NAT failed to allocate IPs or ports for at least one VM in cluster ${CLUSTER_BASENAME}. This typically indicates NAT IP or port exhaustion.`,
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        displayName: `NAT allocation failed in ${CLUSTER_BASENAME}`,
        conditionPrometheusQueryLanguage: {
          query: `sum by (gateway_name) (router_googleapis_com:nat_nat_allocation_failed{monitored_resource="nat_gateway", gateway_name=~"nat-${CLUSTER_BASENAME}-gw.*"}) > 0`,
          ...prometheusDefaults,
        },
      },
    ],
  });

  new gcp.monitoring.AlertPolicy('natDroppedSentPacketsAlert', {
    ...baseArgs,
    displayName: `NAT dropped sent packets in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `NAT dropped sent packets in ${CLUSTER_BASENAME}`,
      content: `Cloud NAT is dropping outbound packets in cluster ${CLUSTER_BASENAME}. This can be caused by NAT IP/port exhaustion (OUT_OF_RESOURCES) or endpoint independence conflicts (ENDPOINT_INDEPENDENCE_CONFLICT).`,
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        displayName: `NAT dropped sent packets in ${CLUSTER_BASENAME}`,
        conditionPrometheusQueryLanguage: {
          query: `sum by (gateway_name, reason) (router_googleapis_com:nat_dropped_sent_packets_count{monitored_resource="nat_gateway", gateway_name=~"nat-${CLUSTER_BASENAME}-gw.*"}) > ${natConfig.droppedSentPacketsThreshold}`,
          ...prometheusDefaults,
          // Ignore temporary spikes (likely caused by dynamic port allocation).
          // We mostly care about sustained dropped sent packets,
          // which most often indicates that we don't have enough ports available to the VMs.
          duration: '1200s',
        },
      },
    ],
  });

  new gcp.monitoring.AlertPolicy('natPortUsageAlert', {
    ...baseArgs,
    displayName: `NAT port usage high in ${CLUSTER_BASENAME}`,
    documentation: {
      subject: `NAT port usage high in ${CLUSTER_BASENAME}`,
      content: `Cloud NAT port usage exceeded ${natConfig.thresholdPercent}% of the maximum for at least one NAT gateway in cluster ${CLUSTER_BASENAME}. Consider increasing NAT IPs or ports per VM.`,
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        displayName: `NAT port usage high in ${CLUSTER_BASENAME}`,
        conditionPrometheusQueryLanguage: {
          query: `sum by (gateway_name) ((router_googleapis_com:nat_port_usage{monitored_resource="nat_gateway", gateway_name=~"nat-${CLUSTER_BASENAME}-gw.*"} / 64512) * 100) > ${natConfig.thresholdPercent}`,
          // 64512 is the maximum number of ports per IP for Cloud NAT, as documented here: https://docs.cloud.google.com/nat/docs/ports-and-addresses#ports
          ...prometheusDefaults,
        },
      },
    ],
  });
}

export function installCloudSqlTxIdUtilizationAlert(
  notificationChannel: gcp.monitoring.NotificationChannel
): void {
  new gcp.monitoring.AlertPolicy('txIdUtilizationAlert', {
    alertStrategy: getAlertStrategy(notificationChannel),
    combiner: 'OR',
    notificationChannels: [notificationChannel.name],
    displayName: `High Transaction ID Utilization in ${CLUSTER_BASENAME}`,
    conditions: [
      {
        displayName: `Cloud SQL Database - Transaction ID utilization ${CLUSTER_BASENAME}`,
        conditionThreshold: {
          filter:
            'resource.type = "cloudsql_database" AND metric.type = "cloudsql.googleapis.com/database/postgresql/transaction_id_utilization"',
          aggregations: [
            {
              alignmentPeriod: '3600s',
              crossSeriesReducer: 'REDUCE_NONE',
              perSeriesAligner: 'ALIGN_MIN',
            },
          ],
          comparison: 'COMPARISON_GT',
          duration: '21600s',
          trigger: {
            count: 1,
          },
          thresholdValue: 0.8,
        },
      },
    ],
  });
}

export function installCloudArmorAlerts(
  notificationChannel: gcp.monitoring.NotificationChannel,
  cloudArmorAlertsConfig: CloudArmorAlertsConfig,
  cloudArmorConfig: CloudArmorConfig
): void {
  const { deniedRequestsThreshold } = cloudArmorAlertsConfig;
  const hasPreviewOnlyRules =
    cloudArmorConfig.allRulesPreviewOnly ||
    (cloudArmorConfig.wafRules.enabled && cloudArmorConfig.wafRules.previewOnly);
  // Scoped to the policy of this cluster; other clusters in the same GCP project
  // report to the same metric.
  const policyFilter = `resource.type="network_security_policy" AND resource.label.policy_name="${CLOUD_ARMOR_POLICY_NAME}"`;

  const aggregations = [
    {
      alignmentPeriod: '300s',
      crossSeriesReducer: 'REDUCE_SUM',
      groupByFields: ['metric.label.backend_target_name'],
      perSeriesAligner: 'ALIGN_SUM',
    },
  ];

  const deniedCondition = (
    displayName: string,
    metricType: string
  ): gcp.types.input.monitoring.AlertPolicyCondition => ({
    displayName,
    conditionThreshold: {
      aggregations,
      comparison: 'COMPARISON_GT',
      duration: '0s',
      filter: assertFilterLength(
        `${policyFilter} AND metric.type="${metricType}" AND metric.label.blocked="true"`
      ),
      thresholdValue: deniedRequestsThreshold,
      trigger: {
        count: 1,
      },
    },
  });

  const enforcedDisplayName = `Cloud Armor denied requests in ${CLUSTER_BASENAME}`;
  const previewedDisplayName = `Cloud Armor would deny requests in ${CLUSTER_BASENAME}`;

  const baseArgs = getAlertPolicyBaseArgs(notificationChannel);

  new gcp.monitoring.AlertPolicy('cloudArmorDeniedRequestsAlert', {
    ...baseArgs,
    displayName: enforcedDisplayName,
    documentation: {
      subject: enforcedDisplayName,
      content: [
        `Requests to **${CLUSTER_BASENAME}** were denied by the Cloud Armor security policy \`${CLOUD_ARMOR_POLICY_NAME}\`.`,
        'This is either an abusive client being blocked at the GCP edge, or legitimate traffic that our rules (WAF signatures, IP whitelist, per endpoint throttles, default deny) reject by mistake.',
        'Check the Cloud Armor request logs of the load balancer to see which rule matched.',
      ].join('\n\n'),
      mimeType: 'text/markdown',
    },
    conditions: [
      deniedCondition(enforcedDisplayName, 'networksecurity.googleapis.com/https/request_count'),
      // Rules in preview mode do not actually deny anything, so we also alert on the
      // requests they would have denied; for the WAF rules that previewed signal is
      // exactly the attack detection we want, and while rolling the whole policy out
      // it is the only signal available.
      ...(hasPreviewOnlyRules
        ? [
            deniedCondition(
              previewedDisplayName,
              'networksecurity.googleapis.com/https/previewed_request_count'
            ),
          ]
        : []),
    ],
  });

  // The Cloud Armor metrics only expose whether a request was blocked, not which rule
  // blocked it, so a WAF specific alert has to go through the load balancer request logs.
  if (cloudArmorConfig.wafRules.enabled && cloudArmorConfig.logging.enabled) {
    installCloudArmorWafAlert(notificationChannel, baseArgs);
  }
}

/**
 * Alerts on requests rejected by one of the preconfigured (OWASP CRS based) WAF rules,
 * as opposed to the IP whitelist, the per endpoint throttles or the default deny rule.
 *
 * Cloud Armor metrics have no label for the rule that matched, so this is a log matching
 * alert over the load balancer request logs, which do report the security policy name,
 * the outcome and the priority of the matching rule. Every WAF match is worth looking at
 * individually (it is either an attack or a false positive signature match), so this
 * alerts on the log entries directly instead of counting them into a log based metric:
 * that way the offending request ends up in the notification.
 *
 * Requires the backend request logging of the load balancer to be enabled
 * (`cloudArmor.logging.enabled`), otherwise Cloud Armor decisions never reach Cloud
 * Logging.
 */
function installCloudArmorWafAlert(
  notificationChannel: gcp.monitoring.NotificationChannel,
  baseArgs: AlertPolicyBaseArgs
): void {
  // Rules in preview mode are reported under previewSecurityPolicy and do not actually
  // reject anything; for the WAF rules that previewed signal is exactly the attack
  // detection we want, so both are matched.
  const matchedWafRule = (field: string) =>
    [
      `jsonPayload.${field}.name="${CLOUD_ARMOR_POLICY_NAME}"`,
      `jsonPayload.${field}.outcome="DENY"`,
      `jsonPayload.${field}.priority>=${CLOUD_ARMOR_WAF_RULE_MIN_PRIORITY}`,
      `jsonPayload.${field}.priority<${CLOUD_ARMOR_WAF_RULE_MAX_PRIORITY}`,
    ].join(' AND ');

  // The gateway is fronted by a regional external application load balancer, whose
  // request logs use `http_external_regional_lb_rule`; `http_load_balancer` is accepted
  // as well so the alert keeps working if the gateway ever becomes global.
  const filter =
    ensureTrailingNewline(`resource.type=("http_external_regional_lb_rule" OR "http_load_balancer")
((${matchedWafRule('enforcedSecurityPolicy')}) OR (${matchedWafRule('previewSecurityPolicy')}))`);

  const displayName = `Cloud Armor WAF rule rejections in ${CLUSTER_BASENAME}`;
  new gcp.monitoring.AlertPolicy('cloudArmorWafRejectionsAlert', {
    ...baseArgs,
    alertStrategy: {
      ...getAlertStrategy(notificationChannel),
      // Log matching conditions cannot be aggregated into a threshold, so the
      // notification rate limit is what keeps a flood of matches down to one alert per
      // period. It is required by GCP for log matching policies.
      notificationRateLimit: {
        period: '300s',
      },
    },
    displayName,
    documentation: {
      subject: displayName,
      content: [
        `A request to **${CLUSTER_BASENAME}** matched a WAF (OWASP CRS) rule of the Cloud Armor security policy \`${CLOUD_ARMOR_POLICY_NAME}\`.`,
        'Unlike the generic Cloud Armor alert, this one fires only on attack signature matches (SQL injection, XSS, RCE, ...), not on IP whitelist, throttle or default deny rejections. WAF rules in preview mode are included: they only log, they do not reject.',
        'Rule priority: `${log.extracted_label.enforced_rule_priority}` enforced / `${log.extracted_label.previewed_rule_priority}` previewed.',
        'Request: `${log.extracted_label.request_method} ${log.extracted_label.request_url}` from `${log.extracted_label.remote_ip}` (`${log.extracted_label.user_agent}`).',
        'Check the matching signature id in the `preconfiguredExprIds` field of the log entry to tell an actual attack apart from a false positive on legitimate traffic.',
      ].join('\n\n'),
      mimeType: 'text/markdown',
    },
    conditions: [
      {
        displayName,
        conditionMatchedLog: {
          filter,
          labelExtractors: {
            enforced_rule_priority: 'EXTRACT(jsonPayload.enforcedSecurityPolicy.priority)',
            previewed_rule_priority: 'EXTRACT(jsonPayload.previewSecurityPolicy.priority)',
            remote_ip: 'EXTRACT(httpRequest.remoteIp)',
            request_method: 'EXTRACT(httpRequest.requestMethod)',
            request_url: 'EXTRACT(httpRequest.requestUrl)',
            user_agent: 'EXTRACT(httpRequest.userAgent)',
          },
        },
      },
    ],
  });
}
