// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as automation from '@pulumi/pulumi/automation';
import { runSvProjectForSvs } from '@canton-network/splice-pulumi-sv/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from '../pulumi';
import { downStack, operation } from '../pulumiOperations';
import { runSvCantonForSvs } from '../sv-canton/pulumi';

/** Takes down all the stacks of the sv runbook and returns them. */
export async function downSvRunbookStacks(
  abortController: PulumiAbortController
): Promise<automation.Stack[]> {
  const stacks: automation.Stack[] = [];
  const down = (stack: automation.Stack) => {
    stacks.push(stack);
    return downStack(stack, abortController);
  };
  let operations: Operation[] = [];
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(operation(`down-${svRunbookStack.name}`, down(svRunbookStack)));
  const cantonStacks = runSvCantonForSvs(['sv'], 'down', down, false);
  const svStacks = runSvProjectForSvs(['sv'], 'down', false, down);
  operations = operations.concat(cantonStacks).concat(svStacks);
  await awaitAllOrThrowAllExceptions(operations);
  return stacks;
}
