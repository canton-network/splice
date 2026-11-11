// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as assert from 'assert/strict';

export const istioApiVersion = 'security.istio.io/v1beta1';
export const istioIngressSelector = { matchLabels: { app: 'istio-ingress' } };

/**
 * There doesn't seem to be an istio-level limit on number of IP lists but at
 * some point we probably hit some k8s limits on the size of a definition so we
 * split it into 100-500 IP ranges per policy.
 *
 * For 100k IPs, the difference between a chunk size of 100 vs 500 from scratch
 * is 20min in pulumi vs 130min in pulumi. But we're still concerned about k8s
 * limits on definition size. So if we break 10000 we'll gradually increase
 * the chunk size, 20 IPs at a time, until reaching 500 chunk size for 50k IPs,
 * which at least is tested for up to 100k IPs.
 *
 * Why 20? Too small jumps makes much noisier Pulumi previews. Too large, and we
 * might jump right into a limit only revealed after extensive testing without
 * really knowing where that limit is. 20 is a compromise: only jumps every 200
 * IPs so realignment updates are rare.
 */
export function istioAccessPolicyChunkSize(ipRangesLength: number): number {
  assert.ok(ipRangesLength >= 0, 'nonsense');
  assert.ok(
    ipRangesLength < 250000,
    `${ipRangesLength} IPs untested, consider testing & increasing maximum chunk size`
  );
  const stepSize = 20;
  return Math.max(100, Math.min(500, Math.ceil(ipRangesLength / (stepSize * 100)) * stepSize));
}

function chunkIpRanges(ipRanges: string[]): string[][] {
  const chunkSize = istioAccessPolicyChunkSize(ipRanges.length);
  return Array.from({ length: Math.ceil(ipRanges.length / chunkSize) }, (_, i) =>
    ipRanges.slice(i * chunkSize, i * chunkSize + chunkSize)
  );
}

export interface IstioIpAllowPolicyArgs {
  namePrefix: string;
  namespace: pulumi.Input<string>;
  selector: object;
  ipRanges: pulumi.Output<string[]>;
  to?: object[];
  opts?: pulumi.CustomResourceOptions;
}

export function createIstioIpAllowPolicies(
  args: IstioIpAllowPolicyArgs
): pulumi.Output<k8s.apiextensions.CustomResource[]> {
  const { namePrefix, namespace, selector, ipRanges, to, opts } = args;
  return ipRanges.apply(ranges =>
    chunkIpRanges(ranges).map(
      (chunk, i) =>
        new k8s.apiextensions.CustomResource(
          `${namePrefix}-${i}`,
          {
            apiVersion: istioApiVersion,
            kind: 'AuthorizationPolicy',
            metadata: {
              name: `${namePrefix}-${i}`,
              namespace,
            },
            spec: {
              selector,
              action: 'ALLOW',
              rules: [
                {
                  from: [{ source: { remoteIpBlocks: chunk } }],
                  ...(to ? { to } : {}),
                },
              ],
            },
          },
          opts
        )
    )
  );
}
