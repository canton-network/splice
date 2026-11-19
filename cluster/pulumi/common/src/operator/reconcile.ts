// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ApiException,
  CustomObjectsApi,
  KubeConfig,
  PatchStrategy,
  setHeaderOptions,
} from '@kubernetes/client-node';

const operatorNamespace = 'operator';
const reconcileRequestAnnotation = 'pulumi.com/reconciliation-request';

function isNotFound(e: unknown): boolean {
  return e instanceof ApiException && e.code === 404;
}

/**
 * Annotates the operator Stack CR so that the operator reprocesses the stack, even if the commit did not change.
 * This is a no-op if the cluster is not deployed through the operator, i.e. if the Stack CR does not exist.
 */
export async function requestOperatorStackReconciliation(stackName: string): Promise<void> {
  const kc = new KubeConfig();
  kc.loadFromDefault();
  const api = kc.makeApiClient(CustomObjectsApi);
  const stackRef = {
    group: 'pulumi.com',
    version: 'v1',
    namespace: operatorNamespace,
    plural: 'stacks',
    name: stackName,
  };
  try {
    await api.getNamespacedCustomObject(stackRef);
  } catch (e) {
    if (isNotFound(e)) {
      console.error(
        `[${stackName}] No operator Stack CR found, not requesting a reconciliation from the operator`
      );
      return;
    }
    throw e;
  }
  const requestValue = new Date().toISOString();
  console.error(`[${stackName}] Requesting reconciliation from the operator (${requestValue})`);
  await api.patchNamespacedCustomObject(
    {
      ...stackRef,
      body: { metadata: { annotations: { [reconcileRequestAnnotation]: requestValue } } },
    },
    setHeaderOptions('Content-Type', PatchStrategy.MergePatch)
  );
}
