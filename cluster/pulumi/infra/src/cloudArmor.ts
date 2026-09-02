// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
import { CLUSTER_BASENAME, getDnsNames } from '@canton-network/splice-pulumi-common';
import { PerEndpointLimits } from '@canton-network/splice-pulumi-common/src/ratelimit/envoyRateLimiter';

import * as config from './config';
import {
  allowedPathsCondition,
  hostCondition,
  ipWhitelistRuleChunks,
  matchExpression,
} from './cloudArmorRules';
import { loadIPRanges } from './whitelisting/ipRanges';

// Rule number ranges
const IP_WHITELIST_RULE_MIN = 1000000;
const THROTTLE_BAN_RULE_MIN = 100000000;
const THROTTLE_BAN_RULE_MAX = 200000000;
const DEFAULT_DENY_RULE_NUMBER = 2147483647;
const RULE_SPACING = 100;

// Types for API endpoint throttling/banning configuration
export interface ApiEndpoint {
  name: string;
  path: string;
  hostname: string;
}

export type CloudArmorConfig = config.CloudArmorConfig & {
  predefinedWafRules?: PredefinedWafRule[];
};

type ThrottleConfig = CloudArmorConfig['publicEndpoints'];

export interface PredefinedWafRule {
  name: string;
  action: 'allow' | 'deny' | 'throttle';
  priority?: number;
  preview?: boolean;
  sensitivityLevel?: 'off' | 'low' | 'medium' | 'high';
}

// Regional and Global policies and rules use different types/constructors; most
// of our pulumi code doesn't care about the difference so can use this alias
export type CloudArmorPolicy = gcp.compute.RegionSecurityPolicy;
const CloudArmorPolicy = gcp.compute.RegionSecurityPolicy;
const PolicyRule = gcp.compute.RegionSecurityPolicyRule;

/**
 * Creates a Cloud Armor security policy
 * @param cac loaded configuration
 * @param scanExternalRateLimits Envoy rate limit config
 * @param opts Pulumi resource options
 * @returns The created security policy resource, if enabled
 */
export function configureCloudArmorPolicy(
  cac: CloudArmorConfig,
  scanExternalRateLimits: PerEndpointLimits,
  opts?: pulumi.ComponentResourceOptions
): CloudArmorPolicy | undefined {
  if (!cac.enabled) {
    return undefined;
  }

  // Step 1: Create the security policy
  const name = `waf-whitelist-throttle-ban-${CLUSTER_BASENAME}`;
  const securityPolicy = new CloudArmorPolicy(
    name,
    {
      name,
      description: `Cloud Armor security policy for ${CLUSTER_BASENAME}`,
      type: 'CLOUD_ARMOR', // attachable to backend service only
      // using `rules` to define all rules at once would be fewer Pulumi resources,
      // but the preview would entail changing this array if the rules were changed,
      // making those changes harder to review than with the separate resources
    },
    opts
  );

  const ruleOpts = { ...opts, parent: securityPolicy, deletedWith: securityPolicy };

  // Step 2: Add predefined WAF rules
  if (cac.predefinedWafRules && cac.predefinedWafRules.length > 0) {
    addPredefinedWafRules();
    /*securityPolicy, args.predefinedWafRules, cac.allRulesPreviewOnly, ruleOpts*/
  }

  // Step 3: Add IP whitelisting rules
  addIpWhitelistRules(securityPolicy, cac.allRulesPreviewOnly, ruleOpts);

  // Step 4: Add throttling/banning rules for specific API endpoints
  if (cac.publicEndpoints && !_.isEmpty(cac.publicEndpoints)) {
    addThrottleAndBanRules(
      securityPolicy,
      cac.publicEndpoints,
      scanExternalRateLimits,
      cac.allRulesPreviewOnly,
      ruleOpts
    );
  }

  // Step 5: Add default deny rule
  addDefaultDenyRule(securityPolicy, cac.allRulesPreviewOnly, ruleOpts);

  return securityPolicy;
}

/**
 * Adds predefined WAF rules to a security policy
 */
function addPredefinedWafRules(): void {
  /*
  securityPolicy: Policy,
  rules: PredefinedWafRule[],
  preview: boolean,
  opts: pulumi.ResourceOptions
     */
  // TODO (DACH-NY/canton-network-internal#406) implement
}

/**
 * Allows the internal and SV whitelisted source IPs to reach any host and path.
 *
 * These rules sit at a lower priority number than the public endpoint rules, so they
 * are evaluated first: whitelisted traffic is allowed outright and never throttled.
 *
 * Without them, everything served through the L7 gateway that is not listed in
 * `publicEndpoints` (grafana, prometheus, alertmanager, the SV and wallet UIs, docs,
 * /version, ...) would be dropped by the default deny rule. Cloud Armor runs in front
 * of istio and knows nothing about the istio AuthorizationPolicy whitelisting, which
 * still applies afterwards for the fine grained per-host rules.
 */
function addIpWhitelistRules(
  securityPolicy: CloudArmorPolicy,
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  // only the internal and SV whitelists, not the full set of external ranges
  loadIPRanges(true).apply(ranges => {
    const chunks = ipWhitelistRuleChunks(ranges, THROTTLE_BAN_RULE_MIN - IP_WHITELIST_RULE_MIN);

    return chunks.map(
      (chunk, i) =>
        new PolicyRule(
          `ip-whitelist-${i}`,
          {
            securityPolicy: securityPolicy.name,
            region: securityPolicy.region,
            description: `Allow whitelisted source IPs (${i + 1} of ${chunks.length})`,
            priority: IP_WHITELIST_RULE_MIN + i,
            preview,
            action: 'allow',
            match: {
              versionedExpr: 'SRC_IPS_V1',
              config: {
                srcIpRanges: chunk,
              },
            },
          },
          opts
        )
    );
  });
}

/**
 * Adds allow/throttle rules for the publicly reachable API endpoints. Any endpoint
 * without a matching rule here is blocked by the default deny rule.
 *
 * Throttling is enforced per source IP. Cloud Armor only ever applies one rate limit
 * rule per request (first match by priority wins, and conforming requests are allowed
 * outright), so a global cap cannot be stacked on top of these rules; it is enforced by
 * envoy instead.
 */
function addThrottleAndBanRules(
  securityPolicy: CloudArmorPolicy,
  throttles: ThrottleConfig,
  scanExternalRateLimits: PerEndpointLimits,
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  _.sortBy(Object.entries(throttles), e => e[0]).reduce(
    (priority, [confEntryHead, singleServiceThrottle]) => {
      if (priority >= THROTTLE_BAN_RULE_MAX) {
        throw new Error(
          `Throttle rule priority ${priority} exceeds maximum ${THROTTLE_BAN_RULE_MAX}`
        );
      }

      const {
        hostname,
        hostPrefixRegex,
        pathPrefix,
        restrictToRateLimitedPaths,
        throttleAcrossAllEndpointsPerIp,
      } = singleServiceThrottle;
      const throttled = throttleAcrossAllEndpointsPerIp !== undefined;
      // leave out the rule but consume the priority number if max is 0
      // this makes the pulumi update cleaner if toggling just one service
      const skipRule = throttled && throttleAcrossAllEndpointsPerIp.maxRequestsBeforeHttp429 === 0;

      if (!skipRule) {
        const ruleName = throttled
          ? `throttle-all-endpoints-per-ip-${confEntryHead}`
          : `allow-all-endpoints-all-ips-${confEntryHead}`;
        const pathExpr = allowedPathsCondition(
          confEntryHead,
          scanExternalRateLimits,
          pathPrefix,
          restrictToRateLimitedPaths
        );
        const hostExpr = hostCondition(
          confEntryHead,
          [getDnsNames().cantonDnsName, getDnsNames().daDnsName],
          hostname,
          hostPrefixRegex
        );
        const matchExpr = matchExpression(confEntryHead, pathExpr, hostExpr);

        new PolicyRule(
          ruleName,
          {
            securityPolicy: securityPolicy.name,
            region: securityPolicy.region,
            description: throttled
              ? `Per source IP throttle rule for all ${confEntryHead} API endpoints`
              : `Allow rule for all ${confEntryHead} API endpoints`,
            priority,
            preview: preview || singleServiceThrottle.rulePreviewOnly,
            action: throttled ? 'throttle' : 'allow',
            match: {
              expr: {
                expression: matchExpr,
              },
            },
            ...(throttled
              ? {
                  rateLimitOptions: {
                    enforceOnKey: 'IP',
                    rateLimitThreshold: {
                      count: throttleAcrossAllEndpointsPerIp.maxRequestsBeforeHttp429,
                      intervalSec: throttleAcrossAllEndpointsPerIp.withinIntervalSeconds,
                    },
                    conformAction: 'allow',
                    exceedAction: 'deny(429)', // 429 Too Many Requests
                  },
                }
              : {}),
          },
          opts
        );
      }
      return priority + RULE_SPACING;
    },
    THROTTLE_BAN_RULE_MIN
  );
}

/**
 * Adds a default deny rule to a security policy
 */
function addDefaultDenyRule(
  securityPolicy: CloudArmorPolicy,
  preview: boolean,
  opts: pulumi.ResourceOptions
): void {
  // The default rule is created together with the policy and cannot be added or
  // removed, only patched (the GCP provider turns a create at this priority into a
  // patch, and skips the delete). So we always declare it: dropping the resource when
  // `preview` is set would leave whatever action was last applied in place, i.e. a
  // previously enforced deny would silently keep denying.
  new PolicyRule(
    'default-deny',
    {
      securityPolicy: securityPolicy.name,
      region: securityPolicy.region,
      description: preview
        ? 'Default rule allowing all other traffic, as all rules are in preview mode'
        : 'Default rule to deny all other traffic',
      priority: DEFAULT_DENY_RULE_NUMBER,
      // default rule cannot be in preview mode; google API gives 400 if you try.
      // we assume that if you want all rules in preview, you *also* still want to
      // allow all traffic.
      preview: false,
      action: preview ? 'allow' : 'deny',
      match: {
        versionedExpr: 'SRC_IPS_V1',
        config: {
          srcIpRanges: ['*'],
        },
      },
    },
    opts
  );
}
