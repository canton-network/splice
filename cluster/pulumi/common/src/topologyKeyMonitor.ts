// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as tls from '@pulumi/tls';
import * as crypto from 'crypto';

import { CnChartVersion } from './artifacts';
import { TopologyKeyMonitorConfig } from './config/topologyKeyMonitorConfig';
import {
  CnInput,
  InstalledHelmChart,
  installSpliceHelmChart,
  SpliceCustomResourceOptions,
} from './helm';
import { ExactNamespace } from './namespace';

export function installTopologyKeyMonitor(
  xns: ExactNamespace,
  participantAddress: CnInput<string>,
  config: TopologyKeyMonitorConfig,
  version: CnChartVersion,
  serviceAccountName?: string,
  disableParticipantAdminApiAuth?: boolean,
  opts?: SpliceCustomResourceOptions
): InstalledHelmChart | undefined {
  if (!config.enable) {
    return undefined;
  }
  if (config.keys.length === 0) {
    void pulumi.log.info(
      `Topology key monitor is enabled for ${xns.logicalName} but no keys are configured. Skipping...`
    );
    return undefined;
  }
  void pulumi.log.info(`Installing topology key monitor in ${xns.logicalName}`);
  return installSpliceHelmChart(
    xns,
    'topology-key-monitor',
    'splice-topology-key-monitor',
    {
      participant: {
        address: participantAddress,
        disableAdminApiAuth: disableParticipantAdminApiAuth ?? false,
      },
      keys: config.keys,
      expectedSynchronizerAliases: config.expectedSynchronizerAliases,
      pollIntervalSeconds: config.pollIntervalSeconds,
      slowQueryThresholdSeconds: config.slowQueryThresholdSeconds,
      missingSynchronizerGracePeriodSeconds: config.missingSynchronizerGracePeriodSeconds,
      ...(config.resources ? { resources: config.resources } : {}),
      ...(serviceAccountName ? { serviceAccountName } : {}),
    },
    config.chartVersion ? CnChartVersion.parse(config.chartVersion) : version,
    opts
  );
}

export function installTopologyKeyMonitorAdminApiTokenSecrets(
  xns: ExactNamespace
): Array<pulumi.Resource> {
  // There is no built-in rotation for this resource. It can be manually rotated by forcing recreation with:
  // pulumi up --replace '<urn of this resource>'
  const signingKey = new tls.PrivateKey(
    `${xns.logicalName}-topology-key-monitor-admin-api-token-signing-key`,
    {
      algorithm: 'RSA',
      rsaBits: 2048,
    }
  );
  const publicKey = new tls.SelfSignedCert(
    `${xns.logicalName}-topology-key-monitor-admin-api-token-public-key`,
    {
      privateKeyPem: signingKey.privateKeyPem,
      subject: { commonName: 'splice-topology-key-monitor' },
      validityPeriodHours: 24 * 365, // surely no one will keep this working without rotating for more than a year
      earlyRenewalHours: 24 * 90, // renew 90 days before expiration
      allowedUses: ['digital_signature'],
    }
  );
  const token = signJwt(signingKey.privateKeyPem, {
    sub: 'topology-key-monitor',
    aud: 'topology-key-monitor-admin-api',
  });

  return [
    new k8s.core.v1.Secret(`${xns.logicalName}-topology-key-monitor-admin-api-token`, {
      metadata: {
        namespace: xns.ns.metadata.name,
        name: `topology-key-monitor-admin-api-token`,
      },
      stringData: {
        token: token,
      },
    }),
    new k8s.core.v1.ConfigMap(`${xns.logicalName}-topology-key-monitor-admin-api-public-key`, {
      metadata: {
        namespace: xns.ns.metadata.name,
        name: `topology-key-monitor-admin-api-token-public-key`,
      },
      data: {
        publicKey: publicKey.certPem,
      },
    }),
  ];
}

function signJwt(signingKeyPem: pulumi.Output<string>, claims: object): pulumi.Output<string> {
  const input = `${encodeForJwt({ alg: 'RS256', typ: 'JWT' })}.${encodeForJwt(claims)}`;
  const signature = signingKeyPem.apply(key => {
    if (key === undefined) {
      // This is needed for expected files.
      return 'signature';
    }
    return crypto.createSign('RSA-SHA256').update(input).sign(key).toString('base64url');
  });
  return pulumi.interpolate`${input}.${signature}`;
}

function encodeForJwt(object: unknown): string {
  return Buffer.from(JSON.stringify(object)).toString('base64url');
}
