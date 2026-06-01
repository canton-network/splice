// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { runSvProjectForSvs } from '@lfdecentralizedtrust/splice-pulumi-sv/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from '../pulumi';
import { upOperation, upStack } from '../pulumiOperations';
import { runSvCantonForSvs } from '../sv-canton/pulumi';

const abortController = new PulumiAbortController();

async function runRunbookUp() {
  let operations: Operation[] = [];
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(upOperation(svRunbookStack, abortController));
  const cantonStacks = runSvCantonForSvs(
    ['sv'],
    'up',
    async stack => {
      await upStack(stack, abortController);
    },
    false
  );
  operations = operations.concat(cantonStacks);
  const svStacks = runSvProjectForSvs(['sv'], 'up', false, async stack => {
    await upStack(stack, abortController);
  });
  operations = operations.concat(svStacks);
  await awaitAllOrThrowAllExceptions(operations);
}

runRunbookUp().catch(() => {
  console.error('Failed to run up');
  process.exit(1);
});
