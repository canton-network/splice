// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PulumiAbortController } from '../pulumi';
import { requestOperatorReconciliation } from '../pulumiOperations';
import { downSvRunbookStacks } from './pulumiDown';

const abortController = new PulumiAbortController();

async function resetSvRunbook(): Promise<void> {
  const stacks = await downSvRunbookStacks(abortController);
  // Make the operator (if used) redeploy the runbook, as it would otherwise only do so on a new commit
  await requestOperatorReconciliation(stacks);
}

resetSvRunbook().catch(e => {
  console.error('Failed to run reset');
  console.error(e);
  process.exit(1);
});
