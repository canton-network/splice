// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  clusterSubConfig,
  clusterYamlConfig,
} from '@canton-network/splice-pulumi-common/src/config/config';
import { z } from 'zod';

export const SequencerP2pIngressIstioConfigSchema = z.object({
  enableDedicatedSequencerP2pIngress: z.boolean().default(true),
});

const SequencerP2pIngressConfigSchema = z.object({
  istio: SequencerP2pIngressIstioConfigSchema.prefault({}),
});

/**
 * When enabled, the sequencer BFT P2P API is served by its own istio ingress behind an
 * L4 passthrough load balancer, instead of sharing the HTTP ingress that sits behind the
 * GCP L7 ALB.
 */
export const enableDedicatedSequencerP2pIngress: boolean = SequencerP2pIngressConfigSchema.parse(
  clusterSubConfig('infra')
).istio.enableDedicatedSequencerP2pIngress;

/** Suffix of the istio ingress helm release, deployment and app label serving P2P traffic. */
export const SEQUENCER_P2P_INGRESS_SUFFIX = '-sequencer-p2p';

export const SEQUENCER_P2P_ISTIO_GATEWAY_NAME = 'cn-sequencer-p2p-gateway';

/** The istio Gateway that sequencer BFT P2P VirtualServices must bind to. */
export const sequencerP2pIstioGateway: string = enableDedicatedSequencerP2pIngress
  ? `cluster-ingress/${SEQUENCER_P2P_ISTIO_GATEWAY_NAME}`
  : 'cluster-ingress/cn-http-gateway';
