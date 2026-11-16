// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { validatorRunbookStackName } from '@canton-network/splice-pulumi-common-validator/src/config';
import { spliceEnvConfig } from '@canton-network/splice-pulumi-common/src/config/envConfig';

import { PulumiAbortController, stack } from '../pulumi';
import { downStack, requestOperatorReconciliation } from '../pulumiOperations';

const abortController = new PulumiAbortController();

async function resetValidatorRunbook(): Promise<void> {
  const validatorName =
    spliceEnvConfig.optionalEnv('SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME') || 'validator-runbook';
  const validatorStack = await stack(
    'validator-runbook',
    validatorRunbookStackName(validatorName),
    true,
    { SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME: validatorName }
  );
  await downStack(validatorStack, abortController);
  // Make the operator (if used) redeploy the validator, as it would otherwise only do so on a new commit
  await requestOperatorReconciliation([validatorStack]);
}

resetValidatorRunbook().catch(e => {
  console.error('Failed to run reset');
  console.error(e);
  process.exit(1);
});
