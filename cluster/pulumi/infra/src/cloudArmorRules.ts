// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as _ from 'lodash';
import {
  extractPathPrefixes,
  PerEndpointLimits,
} from '@canton-network/splice-pulumi-common/src/ratelimit/envoyRateLimiter';
import { z } from 'zod';

// limits from https://cloud.google.com/armor/quotas#limits, in which a
// "subexpression" is an arg to && or ||
export const MAX_SUBEXPRESSION_LENGTH = 1024;
export const MAX_EXPRESSION_LENGTH = 2048;

// https://cloud.google.com/armor/quotas#limits
export const MAX_IPS_PER_RULE = 10;
// the default quota is 200 rules per security policy; leave headroom for the
// endpoint, WAF and default rules
export const MAX_IP_WHITELIST_RULES = 150;

/**
 * Splits the whitelisted source IP ranges into per-rule chunks, since a single Cloud
 * Armor rule can only carry MAX_IPS_PER_RULE ranges.
 *
 * @param availablePriorities how many rule priority numbers are reserved for these rules
 */
export function ipWhitelistRuleChunks(ipRanges: string[], availablePriorities: number): string[][] {
  const unique = [...new Set(ipRanges)].sort();
  if (unique.length === 0) {
    throw new Error(
      'No whitelisted IP ranges for Cloud Armor: every internal endpoint would be denied'
    );
  }

  const chunks = _.chunk(unique, MAX_IPS_PER_RULE);
  if (chunks.length > MAX_IP_WHITELIST_RULES) {
    throw new Error(
      `${unique.length} whitelisted IP ranges need ${chunks.length} Cloud Armor rules, ` +
        `which exceeds the ${MAX_IP_WHITELIST_RULES} rule budget (max ${MAX_IPS_PER_RULE} ranges per rule). ` +
        `Consider using a network security address group instead.`
    );
  }
  if (chunks.length > availablePriorities) {
    throw new Error(
      `IP whitelist rules (${chunks.length}) would overlap the throttle rule priority range`
    );
  }
  return chunks;
}

// the OWASP CRS version behind each Cloud Armor rule set generation, see
// https://cloud.google.com/armor/docs/waf-rules. It is part of the opt-out rule ids,
// and there is no way to derive it from the rule set name.
const OWASP_CRS_VERSIONS: Record<string, string> = {
  v33: 'v030301',
  v422: 'v042200',
};

/**
 * One of Cloud Armor's preconfigured WAF rule sets (see
 * https://cloud.google.com/armor/docs/waf-rules), with the individual OWASP CRS
 * signatures we opt out of.
 */
const WafSignatureSchema = z.object({
  // preconfigured rule set name, e.g. 'sqli-v422-stable'
  name: z.string(),
  // https://cloud.google.com/armor/docs/rule-tuning#sensitivity_levels: 1 only
  // evaluates the paranoia level 1 signatures, which are the ones least prone to
  // false positives. If unset, Cloud Armor's default (all levels) applies.
  sensitivity: z.number().int().min(0).max(4).optional(),
  // numeric OWASP CRS ids of the signatures to skip, e.g. '942190' for
  // 'owasp-crs-v042200-id942190-sqli'. These are the signatures that produced false
  // positives on our own traffic.
  optOutRuleIds: z.array(z.string().regex(/^[0-9]+$/, 'numeric OWASP CRS id')).default([]),
});

export const WafRuleGroupSchema = z.object({
  name: z.string().min(1),
  description: z.string(),
  signatures: z.array(WafSignatureSchema).min(1),
});

export const WafRuleGroupsSchema = z
  .array(WafRuleGroupSchema)
  .refine(
    groups => new Set(groups.map(g => g.name)).size === groups.length,
    'WAF rule group names must be unique, they are used as the Cloud Armor rule names'
  );

type WafSignature = z.infer<typeof WafSignatureSchema>;
export type WafRuleGroup = z.infer<typeof WafRuleGroupSchema>;

/**
 * Expands a numeric OWASP CRS id into the full opt-out rule id Cloud Armor expects,
 * e.g. ('sqli-v422-stable', '942190') -> 'owasp-crs-v042200-id942190-sqli'.
 */
function optOutRuleId(signatureName: string, crsId: string): string {
  const match = /^(.*)-(v\d+)-stable$/.exec(signatureName);
  if (!match) {
    throw new Error(
      `Cannot expand opt-out rule id ${crsId}: ${signatureName} is not a versioned OWASP CRS rule set`
    );
  }
  const [, category, generation] = match;
  const crsVersion = OWASP_CRS_VERSIONS[generation];
  if (!crsVersion) {
    throw new Error(
      `Unknown OWASP CRS version for rule set ${signatureName}, add ${generation} to OWASP_CRS_VERSIONS`
    );
  }
  return `owasp-crs-${crsVersion}-id${crsId}-${category}`;
}

function wafSignatureCondition(context: string, signature: WafSignature): string {
  const options = [
    ...(signature.sensitivity !== undefined ? [`'sensitivity': ${signature.sensitivity}`] : []),
    ...(signature.optOutRuleIds && signature.optOutRuleIds.length > 0
      ? [
          `'opt_out_rule_ids': [${signature.optOutRuleIds
            .map(id => `'${optOutRuleId(signature.name, id)}'`)
            .join(', ')}]`,
        ]
      : []),
  ].join(', ');
  return checkSubexpressionLength(
    `${context}/${signature.name}`,
    `evaluatePreconfiguredWaf('${signature.name}', {${options}})`
  );
}

/**
 * Builds the match expression of a WAF rule: the request matches if any of the
 * group's signatures fires.
 */
export function wafRuleExpression(group: WafRuleGroup): string {
  const expr = group.signatures.map(s => wafSignatureCondition(group.name, s)).join(' || ');
  if (expr.length > MAX_EXPRESSION_LENGTH) {
    throw new Error(
      `Cloud Armor WAF expression for ${group.name} exceeds the ${MAX_EXPRESSION_LENGTH} character limit (current: ${expr.length}). ` +
        `Consider splitting the signatures across more rules.`
    );
  }
  return expr;
}

export function checkSubexpressionLength(context: string, expr: string): string {
  if (expr.length > MAX_SUBEXPRESSION_LENGTH) {
    throw new Error(
      `Cloud Armor subexpression for ${context} exceeds the ${MAX_SUBEXPRESSION_LENGTH} character limit (current: ${expr.length}): ${expr}`
    );
  }
  return expr;
}

/**
 * Builds the host match condition for an endpoint, or undefined to match any host.
 *
 * A cluster is served under more than one DNS name (see getDnsNames), so a prefix match
 * has to cover all of them, otherwise traffic on the other name falls through to the
 * default deny rule.
 *
 * @param context config key of the endpoint, only used for error messages
 * @param dnsNames all DNS names the cluster is served under
 * @param hostname exact hostname to match
 * @param hostPrefixRegex RE2 fragment matching the leading label(s) of a per-node hostname
 */
export function hostCondition(
  context: string,
  dnsNames: string[],
  hostname?: string,
  hostPrefixRegex?: string
): string | undefined {
  let hostnameRegex;
  if (hostname) {
    hostnameRegex = _.escapeRegExp(hostname.toLowerCase());
  } else if (hostPrefixRegex) {
    if (dnsNames.length === 0) {
      throw new Error(`No cluster DNS names to build a host condition for ${context}`);
    }
    const dnsAlternatives = dnsNames.map(n => _.escapeRegExp(n.toLowerCase())).join('|');
    hostnameRegex = `(?:${hostPrefixRegex})\\.[\\w-]+\\.(?:${dnsAlternatives})`;
  } else {
    return undefined;
  }
  // the host header may carry a port, and Cloud Armor does not strip it
  return checkSubexpressionLength(
    context,
    `request.headers['host'].lower().matches(R"^${hostnameRegex}(?::[0-9]+)?$")`
  );
}

/**
 * Builds the path match condition for an endpoint.
 *
 * When `restrictToRateLimitedPaths` is set, the condition is narrowed to exactly the
 * paths under `pathPrefix` that the envoy rate limit config knows about. That is only
 * correct for endpoints actually covered by those rate limits (scan and the token
 * registry); for anything else it must be false, otherwise the rule would match none of
 * the endpoint's real paths and the traffic would be denied by the default rule.
 *
 * @param context config key of the endpoint, only used for error messages
 */
export function allowedPathsCondition(
  context: string,
  scanExternalRateLimits: PerEndpointLimits,
  pathPrefix: string,
  restrictToRateLimitedPaths: boolean
): string {
  // normalize before matching, see
  // https://cloud.google.com/armor/docs/configure-security-policies#path-traversal-and-normalization
  const simplePrefixMatch = () =>
    checkSubexpressionLength(
      context,
      `request.path.lower().urlDecode().startsWith(R"${pathPrefix.toLowerCase()}")`
    );

  if (!restrictToRateLimitedPaths || _.isEmpty(scanExternalRateLimits.rateLimits)) {
    return simplePrefixMatch();
  }

  const basePrefix = pathPrefix.endsWith('/') ? pathPrefix : `${pathPrefix}/`;
  const dynamicPathRxs = extractPathPrefixes(scanExternalRateLimits.rateLimits)
    .filter(p => p.startsWith(basePrefix))
    .map(p => _.escapeRegExp(p.substring(basePrefix.length).toLowerCase()));

  if (dynamicPathRxs.length === 0) {
    // no rate limited path lives under this prefix, so there is nothing to narrow to
    return simplePrefixMatch();
  }

  const regexPattern = `${_.escapeRegExp(basePrefix.toLowerCase())}(?:${dynamicPathRxs.join('|')})`;
  const pathExpr = `request.path.lower().urlDecode().matches(R"^${regexPattern}")`;
  if (pathExpr.length > MAX_SUBEXPRESSION_LENGTH) {
    throw new Error(
      `Cloud Armor path expression for ${context} exceeds the ${MAX_SUBEXPRESSION_LENGTH} character limit (current: ${pathExpr.length}). ` +
        `Consider grouping path prefixes more aggressively.`
    );
  }
  return pathExpr;
}

/**
 * Combines the path and host conditions into the full match expression of a rule.
 */
export function matchExpression(
  context: string,
  pathExpr: string,
  hostExpr: string | undefined
): string {
  const expr = [pathExpr, ...(hostExpr ? [hostExpr] : [])].join(' && ');
  if (expr.length > MAX_EXPRESSION_LENGTH) {
    throw new Error(
      `Cloud Armor expression for ${context} exceeds the ${MAX_EXPRESSION_LENGTH} character limit (current: ${expr.length})`
    );
  }
  return expr;
}
